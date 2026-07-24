package dev.o11y.agent.http.client.apache5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.client.apache4.ApacheHttpClientBridge;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Apache5HttpClientBridgeTest {
  private static final byte[] REQUEST =
      "{\"amount\":2500}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE =
      "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  void configurePolicy() {
    System.setProperty(
        HttpBodyPolicyEngine.POLICY_PROPERTY,
        "V|1\n"
            + "E|YXBhY2hlNS10ZXN0|T1VUR09JTkc|YXBwbGljYXRpb24vanNvbg|YXBwbGljYXRpb24vanNvbg|128|YXBhY2hlNS1vdXRnb2luZy10ZXN0|false||\n"
            + "C|YXBhY2hlNS10ZXN0|REQUEST_PATH||EQUALS|L2FwaS9yZW1vdGU\n"
            + "C|YXBhY2hlNS10ZXN0|REQUEST_METHOD||EQUALS|UE9TVA\n"
            + "F|YXBhY2hlNS10ZXN0|REQUEST_BODY|YW1vdW50|dGVzdC5yZXF1ZXN0LmFtb3VudA|DOUBLE|SPAN|RANGE||T1RIRVI|\n"
            + "F|YXBhY2hlNS10ZXN0|RESPONSE_BODY|c3RhdHVz|dGVzdC5yZXNwb25zZS5zdGF0dXM|STRING|SPAN|ENUM||T1RIRVI|\n");
  }

  @AfterEach
  void clearPolicy() {
    System.clearProperty(HttpBodyPolicyEngine.POLICY_PROPERTY);
    HttpBodyPolicyEngine.captureLimit("OUTGOING", "GET", "/none", "");
  }

  @Test
  void classicBridgeRestoresRequestAndReplaysResponse() throws Exception {
    HttpPost request = new HttpPost("https://example.test/api/remote");
    ByteArrayEntity original =
        new ByteArrayEntity(REQUEST, ContentType.APPLICATION_JSON);
    request.setEntity(original);

    ApacheHttpClientBridge.State state = ApacheHttpClientBridge.enter(null, request);
    assertNotSame(original, request.getEntity());
    ByteArrayOutputStream transmitted = new ByteArrayOutputStream();
    request.getEntity().writeTo(transmitted);
    assertArrayEquals(REQUEST, transmitted.toByteArray());

    BasicClassicHttpResponse response = new BasicClassicHttpResponse(201);
    response.setEntity(new ByteArrayEntity(RESPONSE, ContentType.APPLICATION_JSON));
    ApacheHttpClientBridge.exit(state, response, null);

    assertEquals(original, request.getEntity());
    assertArrayEquals(RESPONSE, EntityUtils.toByteArray(response.getEntity()));
  }

  @Test
  void simpleAsyncBridgeCompletesThroughWrappedCallbackWithoutMutatingBodies() {
    SimpleHttpRequest request =
        SimpleHttpRequest.create("POST", "https://example.test/api/remote");
    request.setBody(REQUEST, ContentType.APPLICATION_JSON);
    AtomicBoolean completed = new AtomicBoolean();
    FutureCallback<SimpleHttpResponse> original =
        new FutureCallback<>() {
          @Override
          public void completed(SimpleHttpResponse response) {
            completed.set(true);
          }

          @Override
          public void failed(Exception failure) {
            throw new AssertionError(failure);
          }

          @Override
          public void cancelled() {
            throw new AssertionError("unexpected cancellation");
          }
        };

    Apache5SimpleAsyncBridge.State state =
        Apache5SimpleAsyncBridge.enter(request, original);
    Apache5SimpleAsyncBridge.exit(state, null);
    @SuppressWarnings("unchecked")
    FutureCallback<SimpleHttpResponse> callback =
        (FutureCallback<SimpleHttpResponse>) state.callback();
    SimpleHttpResponse response = SimpleHttpResponse.create(201, RESPONSE);
    response.setHeader("Content-Type", "application/json");
    callback.completed(response);

    assertTrue(completed.get());
    assertArrayEquals(REQUEST, request.getBodyBytes());
    assertArrayEquals(RESPONSE, response.getBodyBytes());
  }
}
