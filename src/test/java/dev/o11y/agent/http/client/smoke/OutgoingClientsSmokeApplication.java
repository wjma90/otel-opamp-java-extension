package dev.o11y.agent.http.client.smoke;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/** Standalone child process used by the real OUTGOING Java Agent integration test. */
public final class OutgoingClientsSmokeApplication {
  private static final Pattern LIBRARY =
      Pattern.compile("\\\"library\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern AMOUNT =
      Pattern.compile("\\\"amount\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

  private OutgoingClientsSmokeApplication() {}

  public static void main(String[] args) throws Exception {
    ExchangeHandler handler = new ExchangeHandler();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);
    server.createContext("/api/outgoing", handler);
    server.createContext("/api/outgoing-final", handler);
    server.start();
    String endpoint =
        "http://127.0.0.1:"
            + server.getAddress().getPort()
            + "/api/outgoing?campaign=JULY";

    try {
      invokeSpring(endpoint, "spring", 100);
      invokeApache(endpoint, "apache", 200);
      // The default constructor bypasses Builder.build() in OkHttp 3.x, 4.x and 5.x. Keeping this
      // path in the real-agent smoke test prevents that supported construction style from regressing.
      OkHttpClient defaultClient = new OkHttpClient();
      OkHttpClient builtClient = new OkHttpClient.Builder().build();
      try {
        invokeOkHttpSync(defaultClient, endpoint, "okhttp-sync", 300);
        invokeOkHttpAsync(builtClient, endpoint, "okhttp-async", 400);
      } finally {
        closeOkHttp(defaultClient);
        closeOkHttp(builtClient);
      }
      SpringWebClientSmokeCall.invoke(endpoint, "webclient", 500);
      JdkHttpClientSmokeCall.invokeSync(endpoint, "jdk-sync", 600);
      JdkHttpClientSmokeCall.invokeAsync(endpoint, "jdk-async", 700);
      Apache5ClientSmokeCall.invokeClassic(endpoint, "apache5", 800);
      Apache5ClientSmokeCall.invokeAsync(endpoint, "apache5-async", 900);

      handler.verify("spring", requestJson("spring", 100));
      handler.verify("apache", requestJson("apache", 200));
      handler.verify("okhttp-sync", requestJson("okhttp-sync", 300));
      handler.verify("okhttp-async", requestJson("okhttp-async", 400));
      handler.verify("webclient", requestJson("webclient", 500));
      handler.verify("jdk-sync", requestJson("jdk-sync", 600));
      handler.verify("jdk-async", requestJson("jdk-async", 700));
      handler.verify("apache5", requestJson("apache5", 800));
      handler.verify("apache5-async", requestJson("apache5-async", 900));
      require(handler.requestCount() == 9, "server received a different request count");
      require(handler.networkRequestCount() == 10, "OkHttp redirect was not followed exactly once");
      require(handler.redirectCount() == 1, "server emitted a different redirect count");
      System.out.println("O11Y_OUTGOING_BODIES_PRESERVED=9");
      System.out.flush();

      // Let periodic metric and batch log exporters observe all four calls.
      Thread.sleep(1800);
    } finally {
      server.stop(0);
      executor.shutdownNow();
      executor.awaitTermination(3, TimeUnit.SECONDS);
    }

    // Allow Java Agent SDK shutdown hooks to flush the final cumulative point.
    System.out.println("O11Y_OUTGOING_SMOKE_OK");
    System.out.flush();
    Thread.sleep(1200);
  }

  private static void invokeSpring(String endpoint, String library, double amount)
      throws Exception {
    // Keep Spring types out of this JVM entry point. The launcher may verify the main class before
    // premain installs the Java Agent transformer; real application service classes load later too.
    Class<?> caller =
        Class.forName("dev.o11y.agent.http.client.smoke.SpringRestClientSmokeCall");
    Method invoke = caller.getDeclaredMethod("invoke", String.class, String.class, double.class);
    if (!invoke.canAccess(null)) {
      invoke.setAccessible(true);
    }
    try {
      invoke.invoke(null, endpoint, library, amount);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw error;
    }
  }

  private static void invokeApache(String endpoint, String library, double amount)
      throws IOException {
    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpPost request = new HttpPost(endpoint);
      request.setHeader("X-O11y-Smoke-Client", library);
      request.setEntity(
          new StringEntity(requestJson(library, amount), ContentType.APPLICATION_JSON));
      try (CloseableHttpResponse response = client.execute(request)) {
        require(response.getStatusLine().getStatusCode() == 201, "Apache response status changed");
        String resultHeader =
            response.getFirstHeader("X-O11y-Smoke-Result") == null
                ? null
                : response.getFirstHeader("X-O11y-Smoke-Result").getValue();
        verifyResponse(library, amount, resultHeader, EntityUtils.toString(response.getEntity()));
      }
    }
  }

  private static void invokeOkHttpSync(
      OkHttpClient client, String endpoint, String library, double amount) throws IOException {
    Request request = okHttpRequest(endpoint, library, amount);
    try (Response response = client.newCall(request).execute()) {
      verifyOkHttpResponse(library, amount, response);
    }
  }

  private static void invokeOkHttpAsync(
      OkHttpClient client, String endpoint, String library, double amount) throws Exception {
    AsyncCallback callback = new AsyncCallback(library, amount);
    client.newCall(okHttpRequest(endpoint, library, amount)).enqueue(callback);
    require(callback.await(), "OkHttp asynchronous request timed out");
    if (callback.failure() != null) {
      throw new IllegalStateException("OkHttp asynchronous request failed", callback.failure());
    }
  }

  private static void closeOkHttp(OkHttpClient client) {
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdownNow();
  }

  private static Request okHttpRequest(
      String endpoint, String library, double amount) {
    RequestBody body =
        RequestBody.create(
            requestJson(library, amount), MediaType.get("application/json; charset=utf-8"));
    return new Request.Builder()
        .url(endpoint)
        .header("X-O11y-Smoke-Client", library)
        .post(body)
        .build();
  }

  private static void verifyOkHttpResponse(String library, double amount, Response response)
      throws IOException {
    require(response.code() == 201, "OkHttp response status changed");
    require(response.body() != null, "OkHttp response body disappeared");
    verifyResponse(
        library,
        amount,
        response.header("X-O11y-Smoke-Result"),
        response.body().string());
  }

  static void verifyResponse(
      String library, double requestAmount, String resultHeader, String body) {
    require(
        ("approved-" + library).equals(resultHeader),
        library + " response header was not preserved");
    require(body != null && body.contains("\"status\":\"APPROVED\""),
        library + " response JSON was not preserved");
    require(
        body.contains("\"acceptedAmount\":" + number(requestAmount / 2)),
        library + " response amount was not preserved");
  }

  static String requestJson(String library, double amount) {
    return "{\"operation\":\"OUTGOING_SMOKE\",\"library\":\""
        + library
        + "\",\"amount\":"
        + number(amount)
        + '}';
  }

  private static String responseJson(String library, double amount) {
    return "{\"status\":\"APPROVED\",\"library\":\""
        + library
        + "\",\"acceptedAmount\":"
        + number(amount / 2)
        + '}';
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String text(Matcher matcher, String name) {
    require(matcher.find(), "request JSON has no " + name);
    return matcher.group(1);
  }

  private static double decimal(Matcher matcher, String name) {
    return Double.parseDouble(text(matcher, name));
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static final class ExchangeHandler implements HttpHandler {
    private final Map<String, String> requests = new LinkedHashMap<>();
    private final AtomicInteger networkRequests = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      networkRequests.incrementAndGet();
      byte[] response = new byte[0];
      int status = 500;
      try {
        require("POST".equals(exchange.getRequestMethod()), "server expected POST");
        require(
            exchange.getRequestHeaders().getFirst("Content-Type") != null
                && exchange
                    .getRequestHeaders()
                    .getFirst("Content-Type")
                    .toLowerCase(Locale.ROOT)
                    .startsWith("application/json"),
            "server expected application/json");
        String body =
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String library = text(LIBRARY.matcher(body), "library");
        double amount = decimal(AMOUNT.matcher(body), "amount");
        require(
            library.equals(exchange.getRequestHeaders().getFirst("X-O11y-Smoke-Client")),
            library + " request header changed");
        synchronized (requests) {
          requests.put(library, body);
        }
        if ("okhttp-sync".equals(library)
            && "/api/outgoing".equals(exchange.getRequestURI().getPath())) {
          redirects.incrementAndGet();
          exchange
              .getResponseHeaders()
              .set("Location", "/api/outgoing-final?campaign=JULY");
          status = 307;
        } else {
          response = responseJson(library, amount).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
          exchange.getResponseHeaders().set("X-O11y-Smoke-Result", "approved-" + library);
          status = 201;
        }
      } catch (RuntimeException error) {
        response = error.getMessage().getBytes(StandardCharsets.UTF_8);
      }
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    }

    private void verify(String library, String expected) {
      synchronized (requests) {
        require(expected.equals(requests.get(library)), library + " request body changed");
      }
    }

    private int requestCount() {
      synchronized (requests) {
        return requests.size();
      }
    }

    private int networkRequestCount() {
      return networkRequests.get();
    }

    private int redirectCount() {
      return redirects.get();
    }
  }

  private static final class AsyncCallback implements Callback {
    private final String library;
    private final double amount;
    private final CountDownLatch completed = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private AsyncCallback(String library, double amount) {
      this.library = library;
      this.amount = amount;
    }

    @Override
    public void onFailure(Call call, IOException error) {
      failure.set(error);
      completed.countDown();
    }

    @Override
    public void onResponse(Call call, Response response) {
      try (response) {
        verifyOkHttpResponse(library, amount, response);
      } catch (Throwable error) {
        failure.set(error);
      } finally {
        completed.countDown();
      }
    }

    private boolean await() throws InterruptedException {
      return completed.await(8, TimeUnit.SECONDS);
    }

    private Throwable failure() {
      return failure.get();
    }
  }
}
