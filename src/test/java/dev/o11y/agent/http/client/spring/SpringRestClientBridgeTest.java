package dev.o11y.agent.http.client.spring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultRestClientBuilder;

class SpringRestClientBridgeTest {
  @AfterEach
  void clearPolicy() {
    System.clearProperty(HttpBodyPolicyEngine.POLICY_PROPERTY);
  }

  @Test
  void installsOneInterceptorAndPreservesExactRequestAndResponseBodies() throws Exception {
    enableOutgoingBodyCapture(64);
    DefaultRestClientBuilder builder = new DefaultRestClientBuilder();

    SpringRestClientBridge.install(builder);
    SpringRestClientBridge.install(builder);

    List<ClientHttpRequestInterceptor> interceptors = interceptors(builder);
    assertEquals(1, interceptors.size());
    assertTrue(java.lang.reflect.Proxy.isProxyClass(interceptors.getFirst().getClass()));

    byte[] requestBody = "{\"amount\":2500}".getBytes(StandardCharsets.UTF_8);
    byte[] responseBody = "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
    AtomicReference<byte[]> executedBody = new AtomicReference<>();
    TestResponse original = new TestResponse(201, responseBody);
    ClientHttpRequestExecution execution =
        (request, body) -> {
          executedBody.set(body);
          return original;
        };

    ClientHttpRequestExecution composed = interceptors.getFirst().apply(execution);

    ClientHttpResponse returned =
        composed.execute(
            new TestRequest(
                "POST",
                URI.create("https://rates/api/quote?source=PEN"),
                Map.of("Content-Type", List.of("application/json"))),
            requestBody);

    assertSame(requestBody, executedBody.get(), "the application payload must not be rewritten");
    assertNotSame(original, returned, "a replaying response protects the consumed prefix");
    assertEquals(201, returned.getStatusCode());
    assertEquals(List.of("application/json"), returned.getHeaders().get("Content-Type"));
    assertArrayEquals(responseBody, returned.getBody().readAllBytes());

    returned.close();
    assertTrue(original.closed);
  }

  @Test
  void rethrowsTheExactExecutionFailure() {
    enableOutgoingBodyCapture(64);
    DefaultRestClientBuilder builder = new DefaultRestClientBuilder();
    SpringRestClientBridge.install(builder);
    ClientHttpRequestInterceptor interceptor = interceptors(builder).getFirst();
    IOException failure = new IOException("remote failure");
    ClientHttpRequestExecution execution =
        (request, body) -> {
          throw failure;
        };

    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                interceptor.intercept(
                    new TestRequest(
                        "POST", URI.create("https://rates/api/quote"), Map.of()),
                    "{}".getBytes(StandardCharsets.UTF_8),
                    execution));

    assertSame(failure, actual, "reflection must not wrap the library's original exception");
  }

  @Test
  void replaysTheConsumedPrefixBeforeTheExactResponseReadFailure() throws Exception {
    enableOutgoingBodyCapture(64);
    DefaultRestClientBuilder builder = new DefaultRestClientBuilder();
    SpringRestClientBridge.install(builder);
    ClientHttpRequestInterceptor interceptor = interceptors(builder).getFirst();
    byte[] prefix = "{\"status\":\"APP".getBytes(StandardCharsets.UTF_8);
    IOException failure = new IOException("response body failed");
    FailingResponse original = new FailingResponse(200, prefix, failure);
    ClientHttpRequestExecution execution = (request, body) -> original;

    ClientHttpResponse returned =
        interceptor.intercept(
            new TestRequest(
                "POST",
                URI.create("https://rates/api/quote"),
                Map.of("Content-Type", List.of("application/json"))),
            "{}".getBytes(StandardCharsets.UTF_8),
            execution);

    assertNotSame(original, returned, "a read failure also requires a replaying response");
    ByteArrayOutputStream observed = new ByteArrayOutputStream();
    InputStream replay = returned.getBody();
    byte[] buffer = new byte[32];
    int read = replay.read(buffer);
    observed.write(buffer, 0, read);
    IOException actual = assertThrows(IOException.class, () -> replay.read(buffer));

    assertArrayEquals(prefix, observed.toByteArray());
    assertSame(failure, actual, "the application must observe the original transport failure");
    assertTrue(original.bodyClosed, "the failed transport stream must not leak");
    returned.close();
    assertTrue(original.closed);

    TestResponse nextOriginal = new TestResponse(200, "next".getBytes(StandardCharsets.UTF_8));
    ClientHttpResponse next =
        interceptor.intercept(
            new TestRequest("POST", URI.create("https://rates/api/quote"), Map.of()),
            "{}".getBytes(StandardCharsets.UTF_8),
            (request, body) -> nextOriginal);
    assertNotSame(
        nextOriginal, next, "an IOException must abort and release ownership for the next call");
    assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), next.getBody().readAllBytes());
    next.close();
  }

  @Test
  void silentlySkipsAnUnsupportedBuilderShape() {
    SpringRestClientBridge.install(new Object());
  }

  @Test
  void snapshotsRequestHeadersWithoutExposingMutableApplicationCollections() {
    ArrayList<String> values = new ArrayList<>(List.of("application/json"));
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Content-Type", values);

    SpringRestClientBridge.SpringRequest request =
        new SpringRestClientBridge.SpringRequest(new Object(), "POST", "/api", headers);
    values.set(0, "text/plain");
    headers.clear();

    assertEquals(List.of("application/json"), request.headers().get("Content-Type"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> request.headers().put("X-Test", List.of("value")));
  }

  private static void enableOutgoingBodyCapture(int maxBytes) {
    String id = encoded("outgoing-test");
    System.setProperty(
        HttpBodyPolicyEngine.POLICY_PROPERTY,
        "V|1\n"
            + "E|"
            + id
            + "|"
            + encoded("OUTGOING")
            + "|"
            + encoded("application/json")
            + "|"
            + encoded("application/json")
            + "|"
            + maxBytes
            + "|"
            + encoded("outgoing-test")
            + "|false|"
            + encoded("INFO")
            + "|\n"
            + "F|"
            + id
            + "|REQUEST_BODY|"
            + encoded("amount")
            + "|"
            + encoded("test.request.amount")
            + "|DOUBLE|SPAN|RANGE||"
            + encoded("OTHER")
            + "|\n"
            + "F|"
            + id
            + "|RESPONSE_BODY|"
            + encoded("status")
            + "|"
            + encoded("test.response.status")
            + "|STRING|SPAN|ENUM||"
            + encoded("OTHER")
            + "|\n");
  }

  private static List<ClientHttpRequestInterceptor> interceptors(
      DefaultRestClientBuilder builder) {
    AtomicReference<List<ClientHttpRequestInterceptor>> result = new AtomicReference<>();
    Consumer<List<ClientHttpRequestInterceptor>> snapshot =
        current -> result.set(List.copyOf(current));
    try {
      Method accessor = builder.getClass().getMethod("requestInterceptors", Consumer.class);
      if (!accessor.canAccess(builder)) {
        accessor.setAccessible(true);
      }
      accessor.invoke(builder, snapshot);
      return result.get();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private record TestRequest(
      String getMethod, URI getURI, Map<String, List<String>> getHeaders)
      implements HttpRequest {}

  private static final class TestResponse implements ClientHttpResponse {
    private final int status;
    private final byte[] body;
    private boolean closed;

    private TestResponse(int status, byte[] body) {
      this.status = status;
      this.body = body.clone();
    }

    @Override
    public int getStatusCode() {
      return status;
    }

    @Override
    public Map<String, List<String>> getHeaders() {
      return Map.of("Content-Type", List.of("application/json"));
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class FailingResponse implements ClientHttpResponse {
    private final int status;
    private final byte[] prefix;
    private final IOException failure;
    private boolean bodyClosed;
    private boolean closed;

    private FailingResponse(int status, byte[] prefix, IOException failure) {
      this.status = status;
      this.prefix = prefix.clone();
      this.failure = failure;
    }

    @Override
    public int getStatusCode() {
      return status;
    }

    @Override
    public Map<String, List<String>> getHeaders() {
      return Map.of("Content-Type", List.of("application/json"));
    }

    @Override
    public InputStream getBody() {
      return new InputStream() {
        private int offset;

        @Override
        public int read() throws IOException {
          if (offset < prefix.length) {
            return prefix[offset++] & 0xff;
          }
          throw failure;
        }

        @Override
        public int read(byte[] bytes, int targetOffset, int length) throws IOException {
          if (offset >= prefix.length) {
            throw failure;
          }
          int copied = Math.min(length, prefix.length - offset);
          System.arraycopy(prefix, offset, bytes, targetOffset, copied);
          offset += copied;
          return copied;
        }

        @Override
        public void close() {
          bodyClosed = true;
        }
      };
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
