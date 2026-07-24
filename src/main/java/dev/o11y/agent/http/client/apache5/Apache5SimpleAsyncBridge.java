package dev.o11y.agent.http.client.apache5;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.client.ReflectiveHttpAccess;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.bootstrap.internal.JavaagentHttpClientInstrumenters;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures Apache 5's fully buffered {@code SimpleHttpRequest} asynchronous API. */
public final class Apache5SimpleAsyncBridge {
  private static final String CALLBACK = "org.apache.hc.core5.concurrent.FutureCallback";
  private static final String INSTRUMENTATION_NAME = "dev.o11y.apache-httpclient5-async-policy";
  private static final AsyncAttributesGetter HTTP_ATTRIBUTES = new AsyncAttributesGetter();
  private static final Instrumenter<AsyncRequest, AsyncResponse> INSTRUMENTER =
      JavaagentHttpClientInstrumenters.create(
          INSTRUMENTATION_NAME, HTTP_ATTRIBUTES, RequestHeadersSetter.INSTANCE);

  private Apache5SimpleAsyncBridge() {}

  public static List<String> helperClassNames() {
    String bridge = Apache5SimpleAsyncBridge.class.getName();
    return List.of(
        bridge,
        bridge + "$State",
        bridge + "$CallbackHandler",
        bridge + "$AsyncRequest",
        bridge + "$AsyncResponse",
        bridge + "$AsyncAttributesGetter",
        bridge + "$RequestHeadersSetter");
  }

  public static State enter(Object request, Object callback) {
    OutgoingHttpExchange exchange = null;
    Scope scope = null;
    AsyncRequest telemetryRequest = null;
    Context clientContext = null;
    boolean started = false;
    try {
      String method = ReflectiveHttpAccess.method(request);
      String uri = ReflectiveHttpAccess.uri(request);
      if (!OutgoingHttpExchange.isCaptureRequired(method, uri)) {
        return State.noop(callback);
      }
      telemetryRequest = new AsyncRequest(request, method, uri);
      Context parent = Context.current();
      started = INSTRUMENTER.shouldStart(parent, telemetryRequest);
      clientContext = started ? INSTRUMENTER.start(parent, telemetryRequest) : parent;
      scope = clientContext.makeCurrent();
      Map<String, List<String>> headers = headersWithContentType(request);
      exchange = OutgoingHttpExchange.start(method, uri, headers);
      if (!exchange.isOwner()) {
        scope.close();
        if (started) {
          INSTRUMENTER.end(clientContext, telemetryRequest, null, null);
        }
        return State.noop(callback);
      }
      byte[] body = bytes(request, exchange.requestCaptureLimit());
      try {
        exchange.captureRequest(body);
      } finally {
        Arrays.fill(body, (byte) 0);
      }
      ClassLoader loader = request.getClass().getClassLoader();
      Class<?> callbackType = loader.loadClass(CALLBACK);
      State state =
          new State(
              exchange,
              callback,
              telemetryRequest,
              clientContext,
              scope,
              started);
      state.callback =
          Proxy.newProxyInstance(
              callbackType.getClassLoader(),
              new Class<?>[] {callbackType},
              new CallbackHandler(state));
      return state;
    } catch (Throwable ignored) {
      if (exchange != null) {
        exchange.abort();
      }
      if (scope != null) {
        scope.close();
      }
      if (started && clientContext != null && telemetryRequest != null) {
        INSTRUMENTER.end(clientContext, telemetryRequest, null, ignored);
      }
      return State.noop(callback);
    }
  }

  public static void exit(State state, Throwable failure) {
    if (state == null || state.exchange == null) {
      return;
    }
    state.closeEntryScope();
    if (failure != null) {
      state.abort(failure);
    } else {
      state.exchange.detachOwner();
    }
  }

  private static Map<String, List<String>> headersWithContentType(Object message) {
    Map<String, List<String>> headers =
        new LinkedHashMap<>(ReflectiveHttpAccess.headers(message));
    Object contentType = ReflectiveHttpAccess.invokeNoArgs(message, "getContentType");
    if (contentType != null
        && headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
      headers.put("Content-Type", List.of(String.valueOf(contentType)));
    }
    return headers;
  }

  private static byte[] bytes(Object message, int limit) {
    if (message == null || limit <= 0) {
      return new byte[0];
    }
    Object value = ReflectiveHttpAccess.invokeNoArgs(message, "getBodyBytes");
    if (value instanceof byte[] body) {
      return Arrays.copyOf(body, Math.min(body.length, limit + 1));
    }
    Object text = ReflectiveHttpAccess.invokeNoArgs(message, "getBodyText");
    if (text == null) {
      return new byte[0];
    }
    byte[] body = String.valueOf(text).getBytes(StandardCharsets.UTF_8);
    try {
      return Arrays.copyOf(body, Math.min(body.length, limit + 1));
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  public static final class State {
    private final OutgoingHttpExchange exchange;
    private final Object originalCallback;
    private final AsyncRequest telemetryRequest;
    private final Context clientContext;
    private Scope entryScope;
    private final boolean started;
    private final AtomicBoolean finished = new AtomicBoolean();
    private Object callback;

    private State(
        OutgoingHttpExchange exchange,
        Object originalCallback,
        AsyncRequest telemetryRequest,
        Context clientContext,
        Scope entryScope,
        boolean started) {
      this.exchange = exchange;
      this.originalCallback = originalCallback;
      this.telemetryRequest = telemetryRequest;
      this.clientContext = clientContext;
      this.entryScope = entryScope;
      this.started = started;
    }

    private static State noop(Object callback) {
      State state = new State(null, callback, null, null, null, false);
      state.callback = callback;
      return state;
    }

    public Object callback() {
      return callback;
    }

    private void complete(Object response) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      int status = ReflectiveHttpAccess.status(response);
      Map<String, List<String>> headers = headersWithContentType(response);
      AsyncResponse telemetryResponse = new AsyncResponse(status, headers);
      byte[] body = bytes(response, exchange.responseCaptureLimit(status));
      try {
        exchange.complete(status, headers, body);
      } finally {
        Arrays.fill(body, (byte) 0);
        end(telemetryResponse, null);
      }
    }

    private void abort(Throwable failure) {
      if (finished.compareAndSet(false, true)) {
        exchange.abort();
        end(null, failure);
      }
    }

    private void end(AsyncResponse response, Throwable failure) {
      if (started) {
        INSTRUMENTER.end(clientContext, telemetryRequest, response, failure);
      }
    }

    private synchronized void closeEntryScope() {
      if (entryScope != null) {
        entryScope.close();
        entryScope = null;
      }
    }
  }

  private record CallbackHandler(State state) implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "o11y Apache HttpClient 5 async callback";
          default -> throw new UnsupportedOperationException(method.toGenericString());
        };
      }
      String name = method.getName();
      if ("completed".equals(name) && arguments != null && arguments.length == 1) {
        state.complete(arguments[0]);
      } else if ("failed".equals(name) || "cancelled".equals(name)) {
        Throwable failure =
            "failed".equals(name)
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof Throwable throwable
                ? throwable
                : null;
        state.abort(failure);
      }
      if (state.originalCallback == null) {
        return null;
      }
      try {
        if (!method.canAccess(state.originalCallback)) {
          method.trySetAccessible();
        }
        return method.invoke(
            state.originalCallback, arguments == null ? new Object[0] : arguments);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }

  private record AsyncRequest(Object carrier, String method, String uri) {
    private Map<String, List<String>> headers() {
      return headersWithContentType(carrier);
    }
  }

  private record AsyncResponse(int status, Map<String, List<String>> headers) {}

  private static final class AsyncAttributesGetter
      implements HttpClientAttributesGetter<AsyncRequest, AsyncResponse> {
    @Override
    public String getHttpRequestMethod(AsyncRequest request) {
      return request.method();
    }

    @Override
    public List<String> getHttpRequestHeader(AsyncRequest request, String name) {
      return header(request.headers(), name);
    }

    @Override
    public Integer getHttpResponseStatusCode(
        AsyncRequest request, AsyncResponse response, Throwable error) {
      return response == null || response.status() <= 0 ? null : response.status();
    }

    @Override
    public List<String> getHttpResponseHeader(
        AsyncRequest request, AsyncResponse response, String name) {
      return response == null ? List.of() : header(response.headers(), name);
    }

    @Override
    public String getUrlFull(AsyncRequest request) {
      return request.uri();
    }

    @Override
    public String getServerAddress(AsyncRequest request) {
      try {
        return URI.create(request.uri()).getHost();
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }

    @Override
    public Integer getServerPort(AsyncRequest request) {
      try {
        int port = URI.create(request.uri()).getPort();
        return port < 0 ? null : port;
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }
  }

  private enum RequestHeadersSetter implements TextMapSetter<AsyncRequest> {
    INSTANCE;

    @Override
    public void set(AsyncRequest request, String key, String value) {
      if (request == null || key == null || value == null) {
        return;
      }
      try {
        ReflectiveHttpAccess.invoke(
            request.carrier(), "setHeader", String.class, key, Object.class, value);
      } catch (Throwable ignored) {
        // Unsupported carrier shape: the standard Apache instrumentation can still propagate.
      }
    }
  }

  private static List<String> header(Map<String, List<String>> headers, String name) {
    if (name == null) {
      return List.of();
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return List.of();
  }
}
