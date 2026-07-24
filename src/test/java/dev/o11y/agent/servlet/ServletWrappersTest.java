package dev.o11y.agent.servlet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.policy.PolicyState;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.opentelemetry.javaagent.bootstrap.CallDepth;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServletWrappersTest {
  @AfterEach
  void clearPolicyProperties() {
    String generation = System.getProperty(PolicyState.ACTIVE_GENERATION_PROPERTY, "");
    for (String property :
        new String[] {PolicyState.BODY_COMPILED_PROPERTY, PolicyState.BODY_POLICY_PROPERTY}) {
      System.clearProperty(property);
      if (!generation.isBlank()) {
        System.clearProperty(PolicyState.generationProperty(property, generation));
      }
    }
    System.clearProperty(PolicyState.ACTIVE_GENERATION_PROPERTY);
  }

  @Test
  void restoresCallDepthAcrossNestedFilterAndServletEntrypoints() {
    HttpServletRequest request = request(new byte[0], null);
    HttpServletResponse response = response(new ByteArrayOutputStream());

    ServletExchangeHelper.State filter = ServletExchangeHelper.enter(request, response);
    ServletExchangeHelper.State servlet = ServletExchangeHelper.enter(request, response);
    servlet.exit(null);
    filter.exit(null);

    CallDepth depth = CallDepth.forClass(ServletExchangeHelper.class);
    int restored = depth.getAndIncrement();
    depth.decrementAndGet();
    org.junit.jupiter.api.Assertions.assertEquals(0, restored);
  }

  @Test
  void teesRequestAndResponseWithoutChangingApplicationBytes() throws Exception {
    byte[] requestBytes = "{\"amount\":2500}".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream deliveredResponse = new ByteArrayOutputStream();
    HttpServletRequest request = request(requestBytes, null);
    HttpServletResponse response = response(deliveredResponse);
    CapturingHttpServletResponse wrappedResponse =
        new CapturingHttpServletResponse(response, 65536);
    CapturingHttpServletRequest wrappedRequest =
        new CapturingHttpServletRequest(request, wrappedResponse, 65536);

    assertArrayEquals(requestBytes, wrappedRequest.getInputStream().readAllBytes());
    byte[] responseBytes = "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
    wrappedResponse.getOutputStream().write(responseBytes);

    assertArrayEquals(requestBytes, wrappedRequest.capturedBody());
    assertArrayEquals(responseBytes, wrappedResponse.capturedBody());
    assertArrayEquals(responseBytes, deliveredResponse.toByteArray());
  }

  @Test
  void retainsOneSentinelByteWhenABodyExceedsItsLimit() {
    BoundedBodyCapture capture = new BoundedBodyCapture(4);
    capture.write("12345".getBytes(StandardCharsets.UTF_8), 0, 5);

    org.junit.jupiter.api.Assertions.assertEquals(5, capture.bytes().length);
  }

  @Test
  void overwritesRetainedBodyStorageWhenCleared() {
    BoundedBodyCapture capture = new BoundedBodyCapture(64);
    capture.write("sensitive-body".getBytes(StandardCharsets.UTF_8), 0, 14);

    assertFalse(capture.storageIsZeroedForTest());

    capture.clear();

    assertEquals(0, capture.bytes().length);
    assertTrue(capture.storageIsZeroedForTest());
  }

  @Test
  void asyncAbnormalOutcomesNeverReuseTheDefaultSuccessStatus() {
    assertEquals(
        504,
        ServletExchangeHelper.State.CompletionOutcome.TIMED_OUT.responseStatus(200));
    assertEquals(
        500,
        ServletExchangeHelper.State.CompletionOutcome.FAILED.responseStatus(200));
    assertEquals(
        503,
        ServletExchangeHelper.State.CompletionOutcome.TIMED_OUT.responseStatus(503));
    assertEquals(
        200,
        ServletExchangeHelper.State.CompletionOutcome.COMPLETED.responseStatus(200));
  }

  @Test
  void returnsTheSameReaderAndWriterWhenServletCodeCallsThemRepeatedly() throws Exception {
    CapturingHttpServletResponse response =
        new CapturingHttpServletResponse(response(new ByteArrayOutputStream()), 1024);
    CapturingHttpServletRequest request =
        new CapturingHttpServletRequest(
            request("{}".getBytes(StandardCharsets.UTF_8), null), response, 1024);

    assertSame(request.getReader(), request.getReader());
    assertSame(response.getWriter(), response.getWriter());
  }

  @Test
  void preservesTheCapturingWrappersWhenAsyncStarts() {
    AtomicReference<ServletRequest> asyncRequest = new AtomicReference<>();
    AtomicReference<ServletResponse> asyncResponse = new AtomicReference<>();
    AsyncContext asyncContext =
        (AsyncContext)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {AsyncContext.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    HttpServletRequest request = request(new byte[0], new AsyncCall(asyncRequest, asyncResponse, asyncContext));
    CapturingHttpServletResponse response =
        new CapturingHttpServletResponse(response(new ByteArrayOutputStream()), 1024);
    CapturingHttpServletRequest wrapped = new CapturingHttpServletRequest(request, response, 1024);

    assertSame(asyncContext, wrapped.startAsync());
    assertSame(wrapped, asyncRequest.get());
    assertSame(response, asyncResponse.get());

    assertSame(asyncContext, wrapped.startAsync(request, response.getResponse()));
    assertSame(wrapped, asyncRequest.get());
    assertSame(response, asyncResponse.get());
  }

  @Test
  void wrapsOnlyTheBodySideSelectedByAnAsymmetricCapturePlan() throws Exception {
    PolicyState.applyJson(asymmetricBodyPolicy("RESPONSE_BODY"));
    HttpServletRequest responseOnlyRequest = request(new byte[0], null);
    HttpServletResponse responseOnlyResponse = response(new ByteArrayOutputStream());
    ServletExchangeHelper.State responseOnly =
        ServletExchangeHelper.State.start(responseOnlyRequest, responseOnlyResponse);

    assertSame(responseOnlyRequest, responseOnly.request(responseOnlyRequest));
    assertTrue(responseOnly.response(responseOnlyResponse) instanceof CapturingHttpServletResponse);
    responseOnly.complete();

    PolicyState.applyJson(asymmetricBodyPolicy("REQUEST_BODY"));
    HttpServletRequest requestOnlyRequest = request(new byte[0], null);
    HttpServletResponse requestOnlyResponse = response(new ByteArrayOutputStream());
    ServletExchangeHelper.State requestOnly =
        ServletExchangeHelper.State.start(requestOnlyRequest, requestOnlyResponse);

    assertTrue(requestOnly.request(requestOnlyRequest) instanceof CapturingHttpServletRequest);
    assertSame(requestOnlyResponse, requestOnly.response(requestOnlyResponse));
    requestOnly.complete();
  }

  private HttpServletRequest request(byte[] body, AsyncCall asyncCall) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getInputStream")) {
                return input(body);
              }
              if (method.getName().equals("getCharacterEncoding")) {
                return "UTF-8";
              }
              if (method.getName().equals("getMethod")) {
                return "POST";
              }
              if (method.getName().equals("getRequestURI")) {
                return "/api/asymmetric";
              }
              if (method.getName().equals("startAsync") && asyncCall != null) {
                asyncCall.request.set((ServletRequest) arguments[0]);
                asyncCall.response.set((ServletResponse) arguments[1]);
                return asyncCall.context;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private HttpServletResponse response(ByteArrayOutputStream bytes) {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getOutputStream")) {
                return output(bytes);
              }
              if (method.getName().equals("getCharacterEncoding")) {
                return "UTF-8";
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static ServletInputStream input(byte[] body) {
    ByteArrayInputStream input = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return input.read();
      }

      @Override
      public boolean isFinished() {
        return input.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(jakarta.servlet.ReadListener listener) {}
    };
  }

  private static ServletOutputStream output(ByteArrayOutputStream bytes) {
    return new ServletOutputStream() {
      @Override
      public void write(int value) {
        bytes.write(value);
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(jakarta.servlet.WriteListener listener) {}
    };
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }

  private static String asymmetricBodyPolicy(String source) {
    return """
        {
          "schemaVersion": "1.3",
          "bodyEventPolicies": [{
            "id": "asymmetric-body",
            "ruleName": "Asymmetric body",
            "direction": "INCOMING",
            "eventName": "asymmetric-body",
            "maxBodyBytes": 1024,
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/api/asymmetric"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["POST"]}
            ],
            "fields": [{
              "source": "%s",
              "path": "value",
              "attribute": "asymmetric.value",
              "type": "STRING",
              "destinations": ["SPAN"]
            }]
          }]
        }
        """
        .formatted(source);
  }

  private record AsyncCall(
      AtomicReference<ServletRequest> request,
      AtomicReference<ServletResponse> response,
      AsyncContext context) {}
}
