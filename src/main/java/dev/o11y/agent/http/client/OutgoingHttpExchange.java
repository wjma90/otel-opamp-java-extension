package dev.o11y.agent.http.client;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import dev.o11y.agent.http.runtime.HttpErrorType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded, fail-closed snapshot of one policy-owned outbound HTTP exchange. */
public final class OutgoingHttpExchange {
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String REQUEST_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.request.headers";
  private static final String RESPONSE_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.response.headers";
  private static final ThreadLocal<Deque<OwnershipKey>> ACTIVE_OWNERS =
      ThreadLocal.withInitial(ArrayDeque::new);
  private static final OutgoingHttpExchange NOOP =
      new OutgoingHttpExchange(
          false,
          "",
          "",
          "",
          0,
          0,
          List.of(),
          List.of(),
          false,
          List.of(),
          Map.of(),
          Map.of(),
          Map.of(),
          Context.root(),
          null);

  private final boolean owner;
  private final String generation;
  private final String method;
  private final String path;
  private final int requestCaptureLimit;
  private final int responseCaptureLimit;
  private final List<String> requestHeaderNames;
  private final List<String> responseHeaderNames;
  private final boolean eventCandidate;
  private final List<String> eventResponseHeaderNames;
  private final Map<String, List<String>> eventRequestHeaders;
  private final Map<String, List<String>> requestQuery;
  private final Map<String, List<String>> requestHeaders;
  private final Context context;
  private final OwnershipKey ownershipKey;
  private final BoundedBytes requestBody;
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicBoolean ownershipReleased = new AtomicBoolean();

  private OutgoingHttpExchange(
      boolean owner,
      String generation,
      String method,
      String path,
      int requestCaptureLimit,
      int responseCaptureLimit,
      List<String> requestHeaderNames,
      List<String> responseHeaderNames,
      boolean eventCandidate,
      List<String> eventResponseHeaderNames,
      Map<String, List<String>> eventRequestHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestHeaders,
      Context context,
      OwnershipKey ownershipKey) {
    this.owner = owner;
    this.generation = generation;
    this.method = method;
    this.path = path;
    this.requestCaptureLimit = requestCaptureLimit;
    this.responseCaptureLimit = responseCaptureLimit;
    this.requestHeaderNames = requestHeaderNames;
    this.responseHeaderNames = responseHeaderNames;
    this.eventCandidate = eventCandidate;
    this.eventResponseHeaderNames = eventResponseHeaderNames;
    this.eventRequestHeaders = eventRequestHeaders;
    this.requestQuery = requestQuery;
    this.requestHeaders = requestHeaders;
    this.context = context;
    this.ownershipKey = ownershipKey;
    this.requestBody = new BoundedBytes(requestCaptureLimit);
  }

  /** Claims the outermost supported client library for the current call chain. */
  public static OutgoingHttpExchange start(
      String method, String uri, Map<String, List<String>> requestHeaders) {
    String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    String path = requestPath(uri);
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    HttpBodyPolicyEngine.CapturePlan plan =
        HttpBodyPolicyEngine.capturePlan("OUTGOING", normalizedMethod, path, generation);
    List<String> requestedRequestHeaders = headerNames(REQUEST_HEADERS_PROPERTY, generation);
    List<String> requestedResponseHeaders = headerNames(RESPONSE_HEADERS_PROPERTY, generation);
    boolean eventCandidate =
        HttpBodyPolicyEngine.hasCandidate(
            "OUTGOING", normalizedMethod, path, generation);
    List<String> eventRequestHeaderNames =
        HttpBodyPolicyEngine.requiredRequestHeaderNames(
            "OUTGOING", normalizedMethod, path, generation);
    List<String> eventResponseHeaderNames =
        HttpBodyPolicyEngine.requiredResponseHeaderNames(
            "OUTGOING", normalizedMethod, path, generation);
    List<String> eventRequestQueryNames =
        HttpBodyPolicyEngine.requiredRequestQueryNames(
            "OUTGOING", normalizedMethod, path, generation);
    if (plan.requestLimit() == 0
        && plan.responseLimit() == 0
        && requestedRequestHeaders.isEmpty()
        && requestedResponseHeaders.isEmpty()
        && !eventCandidate) {
      return NOOP;
    }
    Context currentContext = Context.current();
    OwnershipKey key = ownershipKey(currentContext, normalizedMethod, path);
    Deque<OwnershipKey> owners = ACTIVE_OWNERS.get();
    if (key.equals(owners.peek())) {
      return NOOP;
    }
    owners.push(key);
    try {
      return new OutgoingHttpExchange(
          true,
          generation,
          normalizedMethod,
          path,
          plan.requestLimit(),
          plan.responseLimit(),
          requestedRequestHeaders,
          requestedResponseHeaders,
          eventCandidate,
          eventResponseHeaderNames,
          normalizedHeaders(requestHeaders, Set.copyOf(eventRequestHeaderNames)),
          HttpBodyPolicyEngine.selectQueryParameters(
              requestQuery(uri), eventRequestQueryNames),
          normalizedHeaders(requestHeaders, captureHeaderNames(requestedRequestHeaders)),
          currentContext,
          key);
    } catch (Throwable failure) {
      releaseOwner(key);
      throw failure;
    }
  }

  /**
   * Returns whether the active generation needs any policy-owned work for this logical request.
   *
   * <p>Reactive and asynchronous clients use this cheap preflight before creating their client
   * span or wrapping a publisher. The authoritative decision is still made by {@link #start}; a
   * concurrent policy change can therefore only turn the later exchange into a safe no-op.
   */
  public static boolean isCaptureRequired(String method, String uri) {
    String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    String path = requestPath(uri);
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    HttpBodyPolicyEngine.CapturePlan plan =
        HttpBodyPolicyEngine.capturePlan("OUTGOING", normalizedMethod, path, generation);
    return plan.requestLimit() > 0
        || plan.responseLimit() > 0
        || !headerNames(REQUEST_HEADERS_PROPERTY, generation).isEmpty()
        || !headerNames(RESPONSE_HEADERS_PROPERTY, generation).isEmpty()
        || HttpBodyPolicyEngine.hasCandidate(
            "OUTGOING", normalizedMethod, path, generation);
  }

  public boolean isOwner() {
    return owner;
  }

  /**
   * Releases only the caller-thread deduplication claim while keeping the exchange alive.
   *
   * <p>An asynchronous client can start on one thread and finish on another. Retaining a
   * ThreadLocal claim until the callback would poison the initiating thread and attempting to
   * remove it from the callback cannot work. Async bridges call this after the transport has
   * accepted the request; completion still clears all bounded buffers.
   */
  public void detachOwner() {
    releaseOwnership();
  }

  public int captureLimit() {
    return Math.max(requestCaptureLimit, responseCaptureLimit);
  }

  public int requestCaptureLimit() {
    return requestCaptureLimit;
  }

  public int responseCaptureLimit() {
    return responseCaptureLimit;
  }

  /** Refines the response bound after status-only conditions can be evaluated. */
  public int responseCaptureLimit(int responseStatus) {
    if (!owner || finished.get() || responseCaptureLimit == 0) {
      return 0;
    }
    return Math.min(
        responseCaptureLimit,
        HttpBodyPolicyEngine.capturePlanAfterResponse(
                "OUTGOING", method, path, responseStatus, generation)
            .responseLimit());
  }

  /** Remaining request bytes, including the one-byte overflow sentinel. */
  public int remainingRequestCaptureBytes() {
    return finished.get() ? 0 : requestBody.remaining();
  }

  /** Starts an isolated serialization attempt that is committed only after writeTo succeeds. */
  public RequestAttempt beginRequestAttempt() {
    return new RequestAttempt(this, owner && !finished.get() ? requestCaptureLimit : 0);
  }

  public void captureRequest(byte[] bytes) {
    if (!finished.get() && bytes != null) {
      requestBody.write(bytes, 0, bytes.length);
    }
  }

  public void captureRequest(byte[] bytes, int offset, int length) {
    if (!finished.get() && bytes != null) {
      requestBody.write(bytes, offset, length);
    }
  }

  public OutputStream captureRequest(OutputStream target) {
    if (!owner || finished.get() || requestCaptureLimit == 0 || target == null) {
      return target;
    }
    return new CapturingOutputStream(target, requestBody);
  }

  public void complete(
      int responseStatus,
      Map<String, List<String>> responseHeaders,
      byte[] responseBody) {
    if (!owner || !finished.compareAndSet(false, true)) {
      return;
    }
    try {
      Context active = Context.current();
      Context effectiveContext =
          Span.fromContext(active).isRecording() ? active : context;
      Map<String, List<String>> normalizedResponseHeaders =
          normalizedHeaders(responseHeaders, captureHeaderNames(responseHeaderNames));
      Map<String, List<String>> eventResponseHeaders =
          normalizedHeaders(responseHeaders, Set.copyOf(eventResponseHeaderNames));
      captureHeaders(
          Span.fromContext(effectiveContext),
          requestHeaderNames,
          "http.request.header.",
          requestHeaders);
      captureHeaders(
          Span.fromContext(effectiveContext),
          responseHeaderNames,
          "http.response.header.",
          normalizedResponseHeaders);
      if (eventCandidate) {
        byte[] requestBytes = requestBody.bytes();
        try {
          HttpBodyPolicyEngine.process(
              "OUTGOING",
              method,
              path,
              first(requestHeaders, "content-type"),
              first(requestHeaders, "content-encoding"),
              requestBytes,
              responseStatus,
              first(normalizedResponseHeaders, "content-type"),
              first(normalizedResponseHeaders, "content-encoding"),
              responseBody == null ? new byte[0] : responseBody,
              eventRequestHeaders,
              eventResponseHeaders,
              requestQuery,
              effectiveContext,
              generation);
        } finally {
          Arrays.fill(requestBytes, (byte) 0);
        }
      }
    } finally {
      requestBody.clear();
      releaseOwnership();
    }
  }

  public void abort() {
    if (!owner || !finished.compareAndSet(false, true)) {
      return;
    }
    requestBody.clear();
    releaseOwnership();
  }

  /**
   * Completes a transport failure so request-only event metrics can retain the canonical OTel
   * {@code error.type}. Response-dependent rules remain fail-closed when no response exists.
   */
  public void fail(Throwable failure) {
    fail(0, Map.of(), failure);
  }

  /** Completes a failure that happened after response metadata became available. */
  public void fail(
      int responseStatus,
      Map<String, List<String>> responseHeaders,
      Throwable failure) {
    if (!owner || !finished.compareAndSet(false, true)) {
      return;
    }
    try {
      Context active = Context.current();
      Context effectiveContext =
          Span.fromContext(active).isRecording() ? active : context;
      Map<String, List<String>> normalizedResponseHeaders =
          normalizedHeaders(responseHeaders, captureHeaderNames(responseHeaderNames));
      Map<String, List<String>> eventResponseHeaders =
          normalizedHeaders(responseHeaders, Set.copyOf(eventResponseHeaderNames));
      captureHeaders(
          Span.fromContext(effectiveContext),
          requestHeaderNames,
          "http.request.header.",
          requestHeaders);
      captureHeaders(
          Span.fromContext(effectiveContext),
          responseHeaderNames,
          "http.response.header.",
          normalizedResponseHeaders);
      if (eventCandidate) {
        byte[] requestBytes = requestBody.bytes();
        try {
          HttpBodyPolicyEngine.processWithErrorType(
              "OUTGOING",
              method,
              path,
              first(requestHeaders, "content-type"),
              first(requestHeaders, "content-encoding"),
              requestBytes,
              responseStatus,
              first(normalizedResponseHeaders, "content-type"),
              first(normalizedResponseHeaders, "content-encoding"),
              new byte[0],
              eventRequestHeaders,
              eventResponseHeaders,
              requestQuery,
              Map.of(),
              effectiveContext,
              generation,
              HttpErrorType.resolve("OUTGOING", responseStatus, failure));
        } finally {
          Arrays.fill(requestBytes, (byte) 0);
        }
      }
    } finally {
      requestBody.clear();
      releaseOwnership();
    }
  }

  private void releaseOwnership() {
    if (owner && ownershipReleased.compareAndSet(false, true)) {
      releaseOwner(ownershipKey);
    }
  }

  /** Reads at most {@code limit + 1} bytes and returns a stream that replays every consumed byte. */
  public static ReplayBody readAndReplay(InputStream source, int limit) throws IOException {
    if (source == null || limit <= 0) {
      return new ReplayBody(new byte[0], source);
    }
    int storageLimit = (int) Math.min((long) limit + 1L, Integer.MAX_VALUE - 8L);
    byte[] storage = new byte[storageLimit];
    int size = 0;
    try {
      while (size < storageLimit) {
        int read = source.read(storage, size, storageLimit - size);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          continue;
        }
        size += read;
      }
    } catch (IOException failure) {
      Arrays.fill(storage, (byte) 0);
      throw failure;
    }
    byte[] captured = size == storage.length ? storage : Arrays.copyOf(storage, size);
    if (captured != storage) {
      Arrays.fill(storage, (byte) 0);
    }
    try {
      return new ReplayBody(captured, source);
    } finally {
      // ReplayBody owns defensive copies from this point onward. Do not retain the temporary
      // transport buffer after handing off the result.
      Arrays.fill(captured, (byte) 0);
    }
  }

  private static void releaseOwner(OwnershipKey expected) {
    if (expected == null) {
      return;
    }
    Deque<OwnershipKey> owners = ACTIVE_OWNERS.get();
    if (expected.equals(owners.peek())) {
      owners.pop();
    } else {
      owners.removeFirstOccurrence(expected);
    }
    if (owners.isEmpty()) {
      ACTIVE_OWNERS.remove();
    }
  }

  private static void captureHeaders(
      Span span,
      List<String> requestedNames,
      String attributePrefix,
      Map<String, List<String>> headers) {
    if (!span.isRecording()) {
      return;
    }
    for (String name : requestedNames) {
      List<String> values = headers.getOrDefault(name, List.of());
      if (!values.isEmpty()) {
        span.setAttribute(
            AttributeKey.stringArrayKey(attributePrefix + attributeName(name)),
            values.stream().limit(4).map(value -> truncate(value, 256)).toList());
      }
    }
  }

  private static List<String> headerNames(String property, String generation) {
    String value =
        generation == null || generation.isBlank()
            ? System.getProperty(property, "")
            : System.getProperty(property + ".generation." + generation, "");
    return Arrays.stream(value.split(","))
        .map(header -> header.trim().toLowerCase(Locale.ROOT))
        .filter(header -> !header.isEmpty() && header.matches("[a-z0-9!#$%&'*+.^_`|~-]+"))
        .distinct()
        .limit(16)
        .toList();
  }

  private static Map<String, List<String>> normalizedHeaders(
      Map<String, List<String>> headers, Set<String> selectedNames) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          if (name == null || values == null) {
            return;
          }
          String normalizedName = name.toLowerCase(Locale.ROOT);
          if (!selectedNames.contains(normalizedName)) {
            return;
          }
          ArrayList<String> safeValues = new ArrayList<>();
          for (String value : values) {
            if (value != null && safeValues.size() < 4) {
              safeValues.add(truncate(value, 256));
            }
          }
          result.put(normalizedName, List.copyOf(safeValues));
        });
    return Map.copyOf(result);
  }

  private static Set<String> captureHeaderNames(List<String> configured) {
    LinkedHashSet<String> result = new LinkedHashSet<>(configured);
    result.add("content-type");
    result.add("content-encoding");
    return Set.copyOf(result);
  }

  private static OwnershipKey ownershipKey(Context context, String method, String path) {
    String traceId = Span.fromContext(context).getSpanContext().getTraceId();
    if (!Span.fromContext(context).getSpanContext().isValid()) {
      traceId = "context-" + Integer.toUnsignedString(System.identityHashCode(context));
    }
    return new OwnershipKey(traceId, method, path);
  }

  private static String first(Map<String, List<String>> headers, String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? "" : values.getFirst();
  }

  private static String requestPath(String uri) {
    if (uri == null || uri.isBlank()) {
      return "/";
    }
    try {
      String path = URI.create(uri).getRawPath();
      return path == null || path.isBlank() ? "/" : path;
    } catch (IllegalArgumentException ignored) {
      int query = uri.indexOf('?');
      String path = query < 0 ? uri : uri.substring(0, query);
      return path.isBlank() ? "/" : path;
    }
  }

  private static String requestQuery(String uri) {
    if (uri == null || uri.isBlank()) {
      return "";
    }
    try {
      String query = URI.create(uri).getRawQuery();
      return query == null ? "" : query;
    } catch (IllegalArgumentException ignored) {
      int separator = uri.indexOf('?');
      return separator < 0 || separator == uri.length() - 1
          ? ""
          : uri.substring(separator + 1);
    }
  }

  private static String attributeName(String header) {
    return header.replace('-', '_');
  }

  private static String truncate(String value, int limit) {
    return value.substring(0, Math.min(value.length(), limit));
  }

  /**
   * A response prefix plus the stream that transparently replays it.
   *
   * <p>The byte array is copied on ingress and egress. This prevents application code from
   * changing bytes that still have to be replayed and prevents the extension from retaining a
   * caller-owned telemetry buffer.
   */
  public static final class ReplayBody {
    private final byte[] captured;
    private final InputStream stream;

    private ReplayBody(byte[] captured, InputStream remainder) {
      this.captured = captured == null ? new byte[0] : captured.clone();
      this.stream =
          this.captured.length == 0
              ? remainder
              : new SequenceInputStream(
                  new ClearingPrefixStream(this.captured),
                  remainder == null ? InputStream.nullInputStream() : remainder);
    }

    public byte[] captured() {
      return captured.clone();
    }

    public InputStream stream() {
      return stream;
    }
  }

  private record OwnershipKey(String traceId, String method, String path) {}

  /** Replays captured bytes and erases each consumed byte plus all remaining bytes on close. */
  private static final class ClearingPrefixStream extends InputStream {
    private byte[] bytes;
    private int position;

    private ClearingPrefixStream(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public synchronized int read() {
      if (bytes == null || position >= bytes.length) {
        clear();
        return -1;
      }
      int value = Byte.toUnsignedInt(bytes[position]);
      bytes[position++] = 0;
      if (position == bytes.length) {
        clear();
      }
      return value;
    }

    @Override
    public synchronized int read(byte[] target, int offset, int length) {
      if (target == null) {
        throw new NullPointerException("target");
      }
      if (offset < 0 || length < 0 || length > target.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      if (bytes == null || position >= bytes.length) {
        clear();
        return -1;
      }
      int copied = Math.min(length, bytes.length - position);
      System.arraycopy(bytes, position, target, offset, copied);
      Arrays.fill(bytes, position, position + copied, (byte) 0);
      position += copied;
      if (position == bytes.length) {
        clear();
      }
      return copied;
    }

    @Override
    public synchronized int available() {
      return bytes == null ? 0 : bytes.length - position;
    }

    @Override
    public synchronized void close() {
      clear();
    }

    private void clear() {
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
        bytes = null;
      }
    }
  }

  /** Bounded request serialization that becomes visible only when the attempt succeeds. */
  public static final class RequestAttempt {
    private final OutgoingHttpExchange exchange;
    private final BoundedBytes bytes;
    private final AtomicBoolean finished = new AtomicBoolean();

    private RequestAttempt(OutgoingHttpExchange exchange, int limit) {
      this.exchange = exchange;
      this.bytes = new BoundedBytes(limit);
    }

    public int remainingCaptureBytes() {
      return finished.get() ? 0 : bytes.remaining();
    }

    public void capture(byte[] source, int offset, int length) {
      if (!finished.get() && source != null) {
        bytes.write(source, offset, length);
      }
    }

    public void captureByte(int value) {
      if (!finished.get()) {
        bytes.writeByte(value);
      }
    }

    public OutputStream capture(OutputStream target) {
      return target == null || remainingCaptureBytes() == 0
          ? target
          : new CapturingOutputStream(target, bytes);
    }

    public void commit() {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      byte[] captured = bytes.bytes();
      try {
        exchange.replaceRequestBody(captured);
      } finally {
        Arrays.fill(captured, (byte) 0);
        bytes.clear();
      }
    }

    public void discard() {
      if (finished.compareAndSet(false, true)) {
        bytes.clear();
      }
    }
  }

  private void replaceRequestBody(byte[] source) {
    if (owner && !finished.get()) {
      requestBody.replace(source);
    }
  }

  private static final class BoundedBytes {
    private final int storageLimit;
    private byte[] bytes;
    private int size;
    private boolean active;

    private BoundedBytes(int configuredLimit) {
      long requested = Math.max(0L, (long) configuredLimit) + 1L;
      storageLimit = configuredLimit <= 0 ? 0 : (int) Math.min(requested, Integer.MAX_VALUE - 8L);
      active = storageLimit > 0;
    }

    private synchronized void write(byte[] source, int offset, int length) {
      if (!active
          || offset < 0
          || length <= 0
          || offset > source.length
          || length > source.length - offset) {
        return;
      }
      int remaining = storageLimit - size;
      if (remaining > 0) {
        int copied = Math.min(remaining, length);
        ensureCapacity(size + copied);
        System.arraycopy(source, offset, bytes, size, copied);
        size += copied;
      }
    }

    private synchronized void writeByte(int value) {
      if (!active || size >= storageLimit) {
        return;
      }
      ensureCapacity(size + 1);
      bytes[size++] = (byte) value;
    }

    private synchronized int remaining() {
      return active ? storageLimit - size : 0;
    }

    private synchronized byte[] bytes() {
      return !active || size == 0 ? new byte[0] : Arrays.copyOf(bytes, size);
    }

    private synchronized void replace(byte[] source) {
      if (!active) {
        return;
      }
      erase();
      if (source != null) {
        write(source, 0, source.length);
      }
    }

    private synchronized void clear() {
      erase();
      bytes = null;
      active = false;
    }

    private void ensureCapacity(int required) {
      if (bytes != null && bytes.length >= required) {
        return;
      }
      int current = bytes == null ? 0 : bytes.length;
      int capacity = Math.min(storageLimit, Math.max(required, Math.max(32, current * 2)));
      byte[] previous = bytes;
      bytes = previous == null ? new byte[capacity] : Arrays.copyOf(previous, capacity);
      if (previous != null) {
        Arrays.fill(previous, (byte) 0);
      }
    }

    private void erase() {
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
      }
      size = 0;
    }
  }

  private static final class CapturingOutputStream extends FilterOutputStream {
    private final BoundedBytes capture;

    private CapturingOutputStream(OutputStream target, BoundedBytes capture) {
      super(target);
      this.capture = capture;
    }

    @Override
    public void write(int value) throws IOException {
      out.write(value);
      capture.writeByte(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      out.write(bytes, offset, length);
      capture.write(bytes, offset, length);
    }
  }
}
