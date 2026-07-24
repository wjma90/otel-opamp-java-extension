package dev.o11y.agent.http.client.jdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpClientBridgeTest {
  private static final byte[] REQUEST =
      "{\"operation\":\"OUTGOING_SMOKE\",\"amount\":100}"
          .getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE =
      "{\"status\":\"APPROVED\",\"acceptedAmount\":50}"
          .getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  void configurePolicy() {
    System.setProperty(
        HttpBodyPolicyEngine.POLICY_PROPERTY,
        "V|1\n"
            + "E|amRrLXRlc3Q|T1VUR09JTkc|YXBwbGljYXRpb24vanNvbg|YXBwbGljYXRpb24vanNvbg|128|amRrLW91dGdvaW5nLXRlc3Q|false||\n"
            + "C|amRrLXRlc3Q|REQUEST_PATH||EQUALS|L2FwaS9yZW1vdGU\n"
            + "C|amRrLXRlc3Q|REQUEST_METHOD||EQUALS|UE9TVA\n"
            + "F|amRrLXRlc3Q|REQUEST_BODY|YW1vdW50|dGVzdC5yZXF1ZXN0LmFtb3VudA|DOUBLE|SPAN|RANGE||T1RIRVI|\n"
            + "F|amRrLXRlc3Q|RESPONSE_BODY|c3RhdHVz|dGVzdC5yZXNwb25zZS5zdGF0dXM|STRING|SPAN|ENUM||T1RIRVI|\n");
  }

  @AfterEach
  void clearPolicy() {
    System.clearProperty(HttpBodyPolicyEngine.POLICY_PROPERTY);
    HttpBodyPolicyEngine.captureLimit("OUTGOING", "GET", "/none", "");
  }

  @Test
  void wrapsPublishersWithoutChangingRequestOrResponseBytes() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://example.test/api/remote"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(REQUEST))
            .build();
    JdkHttpClientBridge.State state =
        JdkHttpClientBridge.enter(request, HttpResponse.BodyHandlers.ofByteArray());

    HttpRequest wrapped = (HttpRequest) state.request();
    CollectingSubscriber requestBytes = new CollectingSubscriber();
    wrapped.bodyPublisher().orElseThrow().subscribe(requestBytes);
    assertArrayEquals(REQUEST, requestBytes.bytes());

    @SuppressWarnings("unchecked")
    HttpResponse.BodyHandler<byte[]> handler =
        (HttpResponse.BodyHandler<byte[]>) state.responseHandler();
    HttpResponse.BodySubscriber<byte[]> response = handler.apply(new ResponseInfo());
    response.onSubscribe(new UnlimitedSubscription());
    response.onNext(List.of(ByteBuffer.wrap(RESPONSE)));
    response.onComplete();

    assertArrayEquals(RESPONSE, response.getBody().toCompletableFuture().join());
    JdkHttpClientBridge.exit(state, null, null);
  }

  @Test
  void asyncExitReleasesOnlyTheCallerThreadOwnershipClaim() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://example.test/api/remote"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(REQUEST))
            .build();
    JdkHttpClientBridge.State state =
        JdkHttpClientBridge.enter(request, HttpResponse.BodyHandlers.discarding());

    JdkHttpClientBridge.exit(state, null, null);
    OutgoingHttpExchange next =
        OutgoingHttpExchange.start(
            "POST",
            "https://example.test/api/remote",
            Map.of("Content-Type", List.of("application/json")));
    assertTrue(next.isOwner());
    next.abort();

    @SuppressWarnings("unchecked")
    HttpResponse.BodyHandler<Void> handler =
        (HttpResponse.BodyHandler<Void>) state.responseHandler();
    HttpResponse.BodySubscriber<Void> response = handler.apply(new ResponseInfo());
    response.onSubscribe(new UnlimitedSubscription());
    response.onNext(List.of(ByteBuffer.wrap(RESPONSE)));
    response.onComplete();
  }

  @Test
  void asyncCancellationAbortsAnExchangeWhoseBodySubscriberNeverCompletes() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://example.test/api/remote"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(REQUEST))
            .build();
    JdkHttpClientBridge.State state =
        JdkHttpClientBridge.enter(request, HttpResponse.BodyHandlers.discarding());
    CompletableFuture<HttpResponse<Void>> result = new CompletableFuture<>();

    JdkHttpClientBridge.exit(state, result, null);
    assertFalse(state.isFinished());

    assertTrue(result.cancel(true));
    assertTrue(state.isFinished());
  }

  private static final class CollectingSubscriber implements Flow.Subscriber<ByteBuffer> {
    private final ArrayList<Byte> bytes = new ArrayList<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ByteBuffer item) {
      ByteBuffer copy = item.duplicate();
      while (copy.hasRemaining()) {
        bytes.add(copy.get());
      }
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError(throwable);
    }

    @Override
    public void onComplete() {}

    private byte[] bytes() {
      byte[] result = new byte[bytes.size()];
      for (int index = 0; index < result.length; index++) {
        result[index] = bytes.get(index);
      }
      return result;
    }
  }

  private static final class ResponseInfo implements HttpResponse.ResponseInfo {
    @Override
    public int statusCode() {
      return 201;
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(
          Map.of("Content-Type", List.of("application/json")), (name, value) -> true);
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private static final class UnlimitedSubscription implements Flow.Subscription {
    @Override
    public void request(long amount) {
      assertTrue(amount > 0);
    }

    @Override
    public void cancel() {}
  }
}
