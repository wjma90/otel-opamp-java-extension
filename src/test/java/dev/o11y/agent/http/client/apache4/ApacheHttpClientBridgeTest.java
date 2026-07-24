package dev.o11y.agent.http.client.apache4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApacheHttpClientBridgeTest {
  private static final byte[] REQUEST = "{\"amount\":2500}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE =
      "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  void configureOutgoingPolicy() {
    String id = encoded("apache-test");
    System.setProperty(
        HttpBodyPolicyEngine.POLICY_PROPERTY,
        "V|1\n"
            + "E|"
            + id
            + '|'
            + encoded("OUTGOING")
            + '|'
            + encoded("application/json")
            + '|'
            + encoded("application/json")
            + "|128|"
            + encoded("apache-outgoing-test")
            + "|false||\n"
            + "C|"
            + id
            + "|REQUEST_PATH||EQUALS|"
            + encoded("/api/remote")
            + '\n'
            + "C|"
            + id
            + "|REQUEST_METHOD||EQUALS|"
            + encoded("POST")
            + '\n'
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

  @AfterEach
  void clearPolicy() {
    System.clearProperty(HttpBodyPolicyEngine.POLICY_PROPERTY);
    System.clearProperty("o11y.dynamic.policy.active-generation");
    System.clearProperty("o11y.dynamic.http.outgoing.request.headers");
    System.clearProperty("o11y.dynamic.http.outgoing.response.headers");
    HttpBodyPolicyEngine.captureLimit("OUTGOING", "GET", "/none", "");
  }

  @Test
  void capturesRequestOnTheRealWriteAndReplaysKnownResponseWithoutChangingBytes()
      throws Exception {
    FakeEntity originalRequest = new FakeEntity(REQUEST.length, REQUEST);
    FakeRequest request = new FakeRequest(originalRequest);

    ApacheHttpClientBridge.State state = ApacheHttpClientBridge.enter(null, request);

    assertTrue(state.isOwner());
    assertNotSame(originalRequest, request.entity);
    ByteArrayOutputStream transmitted = new ByteArrayOutputStream();
    request.entity.writeTo(transmitted);
    assertArrayEquals(REQUEST, transmitted.toByteArray());
    ByteArrayOutputStream retry = new ByteArrayOutputStream();
    request.entity.writeTo(retry);
    assertArrayEquals(REQUEST, retry.toByteArray());
    assertEquals(
        2, originalRequest.writeCount, "retries still delegate the body exactly once each");

    FakeEntity originalResponse = new FakeEntity(RESPONSE.length, RESPONSE);
    FakeResponse response = new FakeResponse(originalResponse);
    ApacheHttpClientBridge.exit(state, response, null);

    assertSame(originalRequest, request.entity, "exit must restore a reusable request");
    assertNotSame(originalResponse, response.entity);
    assertArrayEquals(RESPONSE, response.entity.getContent().readAllBytes());
    ByteArrayOutputStream copied = new ByteArrayOutputStream();
    response.entity.writeTo(copied);
    assertArrayEquals(RESPONSE, copied.toByteArray());
    assertEquals(1, originalResponse.contentReads);
  }

  @Test
  void capturesAndReplaysUnknownLengthChunkedResponseExactly() throws Exception {
    FakeResponse unknown = new FakeResponse(new FakeEntity(-1, RESPONSE));
    FakeEntity unknownEntity = (FakeEntity) unknown.entity;
    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        unknown,
        null);

    assertNotSame(unknownEntity, unknown.entity);
    assertArrayEquals(RESPONSE, unknown.entity.getContent().readAllBytes());
    ByteArrayOutputStream copied = new ByteArrayOutputStream();
    unknown.entity.writeTo(copied);
    assertArrayEquals(RESPONSE, copied.toByteArray());
    assertEquals(1, unknownEntity.contentReads);
  }

  @Test
  void probesOnlyTheBoundedPrefixAndReplaysOversizedStreamingResponseExactly()
      throws Exception {
    byte[] largeBody = new byte[513];
    for (int index = 0; index < largeBody.length; index++) {
      largeBody[index] = (byte) (index % 251);
    }

    FakeResponse oversized =
        new FakeResponse(new FakeEntity(largeBody.length, largeBody, false));
    FakeEntity oversizedEntity = (FakeEntity) oversized.entity;
    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        oversized,
        null);

    assertNotSame(oversizedEntity, oversized.entity);
    assertEquals(129, oversizedEntity.bytesRead, "only maxBodyBytes + 1 is probed eagerly");
    assertArrayEquals(largeBody, oversized.entity.getContent().readAllBytes());
    assertEquals(largeBody.length, oversizedEntity.bytesRead);
    assertEquals(1, oversizedEntity.contentReads);

    FakeResponse copyResponse =
        new FakeResponse(new FakeEntity(largeBody.length, largeBody, false));
    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        copyResponse,
        null);
    ByteArrayOutputStream copied = new ByteArrayOutputStream();
    copyResponse.entity.writeTo(copied);
    assertArrayEquals(largeBody, copied.toByteArray());
  }

  @Test
  void replaysCapturedPrefixAndOriginalReadFailureWithoutHidingIt() throws Exception {
    byte[] prefix = "partial-json".getBytes(StandardCharsets.UTF_8);
    IOException expected = new IOException("remote stream failed");
    FailingEntity failingEntity = new FailingEntity(prefix, expected);
    FakeResponse response = new FakeResponse(failingEntity);

    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        response,
        null);

    assertNotSame(failingEntity, response.entity);
    InputStream replay = response.entity.getContent();
    byte[] replayedPrefix = new byte[prefix.length];
    for (int index = 0; index < replayedPrefix.length; index++) {
      replayedPrefix[index] = (byte) replay.read();
    }
    assertArrayEquals(prefix, replayedPrefix);
    IOException replayed = assertThrows(IOException.class, replay::read);
    assertSame(expected, replayed);

    ByteArrayOutputStream copied = new ByteArrayOutputStream();
    IOException copiedFailure =
        assertThrows(IOException.class, () -> response.entity.writeTo(copied));
    assertArrayEquals(prefix, copied.toByteArray());
    assertSame(expected, copiedFailure);
    assertEquals(1, failingEntity.contentReads);

    ApacheHttpClientBridge.State next =
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST)));
    assertTrue(next.isOwner(), "a response read failure must abort and release ownership");
    ApacheHttpClientBridge.exit(next, null, new IOException("cleanup"));
  }

  @Test
  void replaysTheCompleteBodyAndExactCloseFailureAndAbortsCapture() throws Exception {
    IOException expected = new IOException("response close failed");
    CloseFailingEntity entity = new CloseFailingEntity(RESPONSE, expected);
    FakeResponse response = new FakeResponse(entity);

    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        response,
        null);

    assertNotSame(entity, response.entity);
    InputStream replay = response.entity.getContent();
    byte[] replayed = replay.readNBytes(RESPONSE.length);
    assertArrayEquals(RESPONSE, replayed);
    assertEquals(-1, replay.read(), "close failures must not be reported as read failures");
    IOException actual = assertThrows(IOException.class, replay::close);
    assertSame(expected, actual);
    assertEquals(1, entity.closeCount);
    InputStream closedEarly = response.entity.getContent();
    IOException closeReplay = assertThrows(IOException.class, closedEarly::close);
    assertSame(expected, closeReplay);

    ApacheHttpClientBridge.State next =
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST)));
    assertTrue(next.isOwner(), "a close failure must not confirm the failed response capture");
    ApacheHttpClientBridge.exit(next, null, new IOException("cleanup"));
  }

  @Test
  void proxyCreationFailureLeavesTheOneShotResponseBodyUntouched() throws Exception {
    SealedOneShotEntity entity = new SealedOneShotEntity(RESPONSE);
    SealedResponse response = new SealedResponse(entity);

    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        response,
        null);

    assertSame(entity, response.entity);
    assertEquals(0, entity.contentReads, "proxy creation must precede the first probe read");
    assertArrayEquals(RESPONSE, response.entity.getContent().readAllBytes());
  }

  @Test
  void responseSetterFailureLeavesTheOneShotResponseBodyUntouched() throws Exception {
    OneShotEntity entity = new OneShotEntity(RESPONSE);
    SetterFailingResponse response = new SetterFailingResponse(entity);

    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        response,
        null);

    assertSame(entity, response.entity);
    assertEquals(0, entity.contentReads, "the holder must be installed before the first probe read");
    assertArrayEquals(RESPONSE, response.entity.getContent().readAllBytes());
  }

  @Test
  void replaysTheExactGetContentFailureBeforeDelegatingLaterAccesses() throws Exception {
    IOException expected = new IOException("getContent failed");
    AccessFailingEntity entity = new AccessFailingEntity(RESPONSE, expected);
    FakeResponse response = new FakeResponse(entity);

    ApacheHttpClientBridge.exit(
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST))),
        response,
        null);

    IOException actual = assertThrows(IOException.class, response.entity::getContent);
    assertSame(expected, actual);
    assertEquals(1, entity.contentReads, "the stored failure is replayed without a second access");
    assertArrayEquals(RESPONSE, response.entity.getContent().readAllBytes());
    assertEquals(2, entity.contentReads);
  }

  @Test
  void restoresTheOriginalRequestAcrossFailureAndReuse() throws Exception {
    FakeEntity original = new FakeEntity(REQUEST.length, REQUEST);
    FakeRequest request = new FakeRequest(original);

    ApacheHttpClientBridge.State first = ApacheHttpClientBridge.enter(null, request);
    EntityApi firstProxy = request.entity;
    assertNotSame(original, firstProxy);
    firstProxy.writeTo(new ByteArrayOutputStream());
    ApacheHttpClientBridge.exit(first, null, new IOException("first execution failed"));
    assertSame(original, request.entity);

    ApacheHttpClientBridge.State retry = ApacheHttpClientBridge.enter(null, request);
    assertNotSame(original, request.entity);
    assertNotSame(firstProxy, request.entity, "reuse must not wrap the previous policy proxy");
    request.entity.writeTo(new ByteArrayOutputStream());
    ApacheHttpClientBridge.exit(retry, null, new IOException("retry cleanup"));

    assertSame(original, request.entity);
    assertEquals(
        2, original.writeCount, "each execution delegates directly to the original entity");
  }

  @Test
  void discardsAPartialAttemptAndCommitsASuccessfulRetry() throws Exception {
    IOException expected = new IOException("first serialization failed");
    RetryingEntity original = new RetryingEntity(REQUEST, expected);
    FakeRequest request = new FakeRequest(original);
    ApacheHttpClientBridge.State state = ApacheHttpClientBridge.enter(null, request);

    ByteArrayOutputStream partial = new ByteArrayOutputStream();
    IOException actual =
        assertThrows(IOException.class, () -> request.entity.writeTo(partial));
    assertSame(expected, actual);
    assertTrue(partial.size() > 0 && partial.size() < REQUEST.length);

    ApacheHttpClientBridge.State nested =
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST)));
    assertFalse(nested.isOwner(), "discard keeps ownership available for an in-flight retry");

    ByteArrayOutputStream retried = new ByteArrayOutputStream();
    request.entity.writeTo(retried);
    assertArrayEquals(REQUEST, retried.toByteArray());
    ApacheHttpClientBridge.exit(
        state, new FakeResponse(new FakeEntity(RESPONSE.length, RESPONSE)), null);

    assertSame(original, request.entity);
    assertEquals(2, original.writeCount);
  }

  @Test
  void abortsCleanlyWhenApacheExecutionThrows() {
    FakeEntity original = new FakeEntity(REQUEST.length, REQUEST);
    FakeRequest request = new FakeRequest(original);
    ApacheHttpClientBridge.State state = ApacheHttpClientBridge.enter(null, request);
    assertTrue(state.isOwner());

    ApacheHttpClientBridge.exit(state, null, new IOException("connection failed"));
    assertSame(original, request.entity, "the exceptional exit must restore the request entity");

    ApacheHttpClientBridge.State next =
        ApacheHttpClientBridge.enter(
            null, new FakeRequest(new FakeEntity(REQUEST.length, REQUEST)));
    assertTrue(next.isOwner(), "the failed call must release the per-thread owner");
    ApacheHttpClientBridge.exit(next, null, new IOException("cleanup"));
  }

  @Test
  void doesNotClaimCallsThatDoNotMatchAnyOutgoingPolicy() {
    FakeRequest request =
        new FakeRequest(new FakeEntity(REQUEST.length, REQUEST), "GET", "/not-matched");
    ApacheHttpClientBridge.State state = ApacheHttpClientBridge.enter(null, request);

    assertFalse(state.isOwner());
    assertTrue(request.entity instanceof FakeEntity);
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private interface EntityApi {
    long getContentLength();

    InputStream getContent() throws IOException;

    void writeTo(OutputStream output) throws IOException;

    boolean isRepeatable();

    boolean isStreaming();

    void consumeContent();
  }

  private static final class FakeEntity implements EntityApi {
    private final long contentLength;
    private final byte[] bytes;
    private final boolean repeatable;
    private int writeCount;
    private int contentReads;
    private int bytesRead;

    private FakeEntity(long contentLength, byte[] bytes) {
      this(contentLength, bytes, true);
    }

    private FakeEntity(long contentLength, byte[] bytes, boolean repeatable) {
      this.contentLength = contentLength;
      this.bytes = bytes;
      this.repeatable = repeatable;
    }

    @Override
    public long getContentLength() {
      return contentLength;
    }

    @Override
    public InputStream getContent() {
      contentReads++;
      return new ByteArrayInputStream(bytes) {
        @Override
        public synchronized int read() {
          int value = super.read();
          if (value >= 0) {
            bytesRead++;
          }
          return value;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
          int read = super.read(target, offset, length);
          if (read > 0) {
            bytesRead += read;
          }
          return read;
        }
      };
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      writeCount++;
      output.write(bytes);
    }

    @Override
    public boolean isRepeatable() {
      return repeatable;
    }

    @Override
    public boolean isStreaming() {
      return false;
    }

    @Override
    public void consumeContent() {}
  }

  private static final class FailingEntity implements EntityApi {
    private final byte[] prefix;
    private final IOException failure;
    private int contentReads;

    private FailingEntity(byte[] prefix, IOException failure) {
      this.prefix = prefix;
      this.failure = failure;
    }

    @Override
    public long getContentLength() {
      return -1;
    }

    @Override
    public InputStream getContent() {
      contentReads++;
      return new InputStream() {
        private int offset;

        @Override
        public int read() throws IOException {
          if (offset == prefix.length) {
            throw failure;
          }
          return prefix[offset++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int targetOffset, int length) throws IOException {
          if (offset == prefix.length) {
            throw failure;
          }
          int copied = Math.min(length, prefix.length - offset);
          System.arraycopy(prefix, offset, bytes, targetOffset, copied);
          offset += copied;
          return copied;
        }
      };
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      output.write(prefix);
      throw failure;
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }

    @Override
    public boolean isStreaming() {
      return true;
    }

    @Override
    public void consumeContent() {}
  }

  private static final class CloseFailingEntity implements EntityApi {
    private final byte[] bytes;
    private final IOException failure;
    private int closeCount;

    private CloseFailingEntity(byte[] bytes, IOException failure) {
      this.bytes = bytes.clone();
      this.failure = failure;
    }

    @Override
    public long getContentLength() {
      return bytes.length;
    }

    @Override
    public InputStream getContent() {
      return new ByteArrayInputStream(bytes) {
        @Override
        public void close() throws IOException {
          closeCount++;
          throw failure;
        }
      };
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      output.write(bytes);
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }

    @Override
    public boolean isStreaming() {
      return true;
    }

    @Override
    public void consumeContent() {}
  }

  private static final class AccessFailingEntity implements EntityApi {
    private final byte[] bytes;
    private final IOException failure;
    private int contentReads;

    private AccessFailingEntity(byte[] bytes, IOException failure) {
      this.bytes = bytes.clone();
      this.failure = failure;
    }

    @Override
    public long getContentLength() {
      return bytes.length;
    }

    @Override
    public InputStream getContent() throws IOException {
      contentReads++;
      if (contentReads == 1) {
        throw failure;
      }
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      output.write(bytes);
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }

    @Override
    public boolean isStreaming() {
      return true;
    }

    @Override
    public void consumeContent() {}
  }

  private static class OneShotEntity implements EntityApi {
    private final byte[] bytes;
    private final InputStream content;
    private int contentReads;

    private OneShotEntity(byte[] bytes) {
      this.bytes = bytes.clone();
      content = new ByteArrayInputStream(this.bytes);
    }

    @Override
    public long getContentLength() {
      return bytes.length;
    }

    @Override
    public InputStream getContent() {
      contentReads++;
      return content;
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      content.transferTo(output);
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }

    @Override
    public boolean isStreaming() {
      return true;
    }

    @Override
    public void consumeContent() {}
  }

  private static final class RetryingEntity implements EntityApi {
    private final byte[] bytes;
    private final IOException firstFailure;
    private int writeCount;

    private RetryingEntity(byte[] bytes, IOException firstFailure) {
      this.bytes = bytes.clone();
      this.firstFailure = firstFailure;
    }

    @Override
    public long getContentLength() {
      return bytes.length;
    }

    @Override
    public InputStream getContent() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
      writeCount++;
      if (writeCount == 1) {
        output.write(bytes, 0, Math.max(1, bytes.length / 3));
        throw firstFailure;
      }
      output.write(bytes);
    }

    @Override
    public boolean isRepeatable() {
      return true;
    }

    @Override
    public boolean isStreaming() {
      return false;
    }

    @Override
    public void consumeContent() {}
  }

  private sealed interface SealedEntityApi permits SealedOneShotEntity {
    InputStream getContent();

    boolean isRepeatable();
  }

  private static final class SealedOneShotEntity implements SealedEntityApi {
    private final InputStream content;
    private int contentReads;

    private SealedOneShotEntity(byte[] bytes) {
      content = new ByteArrayInputStream(bytes.clone());
    }

    @Override
    public InputStream getContent() {
      contentReads++;
      return content;
    }

    @Override
    public boolean isRepeatable() {
      return false;
    }
  }

  private static final class FakeRequest {
    private final String method;
    private final String uri;
    private EntityApi entity;

    private FakeRequest(EntityApi entity) {
      this(entity, "POST", "/api/remote");
    }

    private FakeRequest(EntityApi entity, String method, String uri) {
      this.entity = entity;
      this.method = method;
      this.uri = uri;
    }

    public EntityApi getEntity() {
      return entity;
    }

    public void setEntity(EntityApi entity) {
      this.entity = entity;
    }

    public RequestLine getRequestLine() {
      return new RequestLine(method, uri);
    }

    public Header[] getAllHeaders() {
      return new Header[] {new Header("Content-Type", "application/json")};
    }
  }

  private record RequestLine(String method, String uri) {
    public String getMethod() {
      return method;
    }

    public String getUri() {
      return uri;
    }
  }

  private static final class FakeResponse {
    private EntityApi entity;

    private FakeResponse(EntityApi entity) {
      this.entity = entity;
    }

    public EntityApi getEntity() {
      return entity;
    }

    public void setEntity(EntityApi entity) {
      this.entity = entity;
    }

    public StatusLine getStatusLine() {
      return new StatusLine(200);
    }

    public Header[] getAllHeaders() {
      return new Header[] {new Header("Content-Type", "application/json")};
    }
  }

  private static final class SetterFailingResponse {
    private final EntityApi entity;

    private SetterFailingResponse(EntityApi entity) {
      this.entity = entity;
    }

    public EntityApi getEntity() {
      return entity;
    }

    public void setEntity(EntityApi ignored) throws IOException {
      throw new IOException("setter rejected replacement");
    }

    public StatusLine getStatusLine() {
      return new StatusLine(200);
    }

    public Header[] getAllHeaders() {
      return new Header[] {new Header("Content-Type", "application/json")};
    }
  }

  private static final class SealedResponse {
    private SealedEntityApi entity;

    private SealedResponse(SealedEntityApi entity) {
      this.entity = entity;
    }

    public SealedEntityApi getEntity() {
      return entity;
    }

    public void setEntity(SealedEntityApi entity) {
      this.entity = entity;
    }

    public StatusLine getStatusLine() {
      return new StatusLine(200);
    }

    public Header[] getAllHeaders() {
      return new Header[] {new Header("Content-Type", "application/json")};
    }
  }

  private record StatusLine(int code) {
    public int getStatusCode() {
      return code;
    }
  }

  private record Header(String name, String value) {
    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }
  }
}
