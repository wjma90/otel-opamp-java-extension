package dev.o11y.agent.http.client.okhttp;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.client.ReflectiveHttpAccess;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.bootstrap.internal.JavaagentHttpClientInstrumenters;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-only integration with the application's HTTP client classes.
 *
 * <p>Type names are assembled at runtime so the extension's private, relocated transport cannot
 * be mistaken for an application client by the shade plugin or the matcher.
 */
public final class OkHttpClientBridge {
  private static final String CLIENT_NAMESPACE = "okhttp".concat("3.");
  private static final String IO_NAMESPACE = "ok".concat("io.");
  private static final String INTERNAL_SHADED_PREFIX = "dev.o11y.agent.internal.shaded.";
  private static final String INSTRUMENTATION_NAME = "dev.o11y.okhttp-policy";
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String REQUEST_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.request.headers";
  private static final String RESPONSE_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.response.headers";
  private static final ThreadLocal<ArrayDeque<ActiveCall>> ACTIVE_CALLS =
      ThreadLocal.withInitial(ArrayDeque::new);
  private static final OkHttpAttributesGetter HTTP_ATTRIBUTES = new OkHttpAttributesGetter();
  private static final Instrumenter<OkHttpRequest, OkHttpResponse> INSTRUMENTER =
      JavaagentHttpClientInstrumenters.create(
          INSTRUMENTATION_NAME, HTTP_ATTRIBUTES, RequestHeadersSetter.INSTANCE);

  private OkHttpClientBridge() {}

  public static String clientClassName() {
    return CLIENT_NAMESPACE.concat("OkHttpClient");
  }

  public static String builderClassName() {
    return CLIENT_NAMESPACE.concat("OkHttpClient$Builder");
  }

  public static String interceptorClassName() {
    return CLIENT_NAMESPACE.concat("Interceptor");
  }

  public static String requestBodyClassName() {
    return CLIENT_NAMESPACE.concat("RequestBody");
  }

  public static String bufferedSinkClassName() {
    return IO_NAMESPACE.concat("BufferedSink");
  }

  public static String internalShadedPrefix() {
    return INTERNAL_SHADED_PREFIX;
  }

  /** All bridge classes that must be injected into the application's class loader. */
  public static List<String> helperClassNames() {
    String bridge = OkHttpClientBridge.class.getName();
    return List.of(
        bridge,
        bridge + "$ActiveCall",
        bridge + "$ApplicationInterceptorHandler",
        bridge + "$KnownApplicationType",
        bridge + "$RequestWriteState",
        bridge + "$SinkCaptureHandler",
        bridge + "$SourceCaptureHandler",
        bridge + "$OkHttpRequest",
        bridge + "$OkHttpResponse",
        bridge + "$OkHttpAttributesGetter",
        bridge + "$RequestHeadersSetter");
  }

  /** Installs one logical-call interceptor per mutable builder. */
  public static void install(Object builder) {
    if (builder == null || isInternal(builder.getClass())) {
      return;
    }
    synchronized (builder) {
      if (hasPolicyInterceptor(builder)) {
        return;
      }
      try {
        ClassLoader loader = builder.getClass().getClassLoader();
        Class<?> interceptorType = loadKnownType(loader, KnownApplicationType.INTERCEPTOR);
        Object interceptor =
            Proxy.newProxyInstance(
                interceptorType.getClassLoader(),
                new Class<?>[] {interceptorType},
                new ApplicationInterceptorHandler());
        Method registration =
            findCompatibleMethod(builder.getClass(), "addInterceptor", interceptor);
        invoke(registration, builder, interceptor);
      } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        // Unsupported client shape: preserve the application builder unchanged.
      } catch (Throwable ignored) {
        // A registration method can expose application-specific checked exceptions.
      }
    }
  }

  /** Starts one bounded request serialization attempt without modifying the application body. */
  public static RequestWriteState beginRequestWrite(Object requestBody, Object sink) {
    if (requestBody == null || sink == null || isInternal(requestBody.getClass())) {
      return new RequestWriteState(sink, null);
    }
    ArrayDeque<ActiveCall> calls = ACTIVE_CALLS.get();
    ActiveCall call = calls.peek();
    if (call == null
        || call.requestBody != requestBody
        || call.exchange.requestCaptureLimit() <= 0
        || isCaptureProxy(sink)) {
      return new RequestWriteState(sink, null);
    }
    OutgoingHttpExchange.RequestAttempt attempt = call.exchange.beginRequestAttempt();
    try {
      ClassLoader loader = sink.getClass().getClassLoader();
      Class<?> bufferedSink = loadKnownType(loader, KnownApplicationType.BUFFERED_SINK);
      if (!bufferedSink.isInstance(sink)) {
        attempt.discard();
        return new RequestWriteState(sink, null);
      }
      Object capturingSink =
          Proxy.newProxyInstance(
              bufferedSink.getClassLoader(),
              new Class<?>[] {bufferedSink},
              new SinkCaptureHandler(sink, attempt));
      return new RequestWriteState(capturingSink, attempt);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      attempt.discard();
      return new RequestWriteState(sink, null);
    }
  }

  /** Commits only a fully successful writeTo invocation; failed attempts leave no partial body. */
  public static void finishRequestWrite(RequestWriteState state, Throwable failure) {
    if (state == null || state.attempt == null) {
      return;
    }
    if (failure == null) {
      state.attempt.commit();
    } else {
      state.attempt.discard();
    }
  }

  private static boolean hasPolicyInterceptor(Object builder) {
    Object configured = ReflectiveHttpAccess.invokeNoArgs(builder, "interceptors");
    if (!(configured instanceof Iterable<?> interceptors)) {
      return false;
    }
    for (Object interceptor : interceptors) {
      if (interceptor != null && Proxy.isProxyClass(interceptor.getClass())) {
        try {
          if (Proxy.getInvocationHandler(interceptor) instanceof ApplicationInterceptorHandler) {
            return true;
          }
        } catch (IllegalArgumentException ignored) {
          // Continue inspecting the builder's remaining interceptors.
        }
      }
    }
    return false;
  }

  private static boolean isCaptureProxy(Object sink) {
    if (!Proxy.isProxyClass(sink.getClass())) {
      return false;
    }
    try {
      InvocationHandler handler = Proxy.getInvocationHandler(sink);
      return handler instanceof SinkCaptureHandler;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static boolean isInternal(Class<?> type) {
    return type.getName().startsWith(INTERNAL_SHADED_PREFIX);
  }

  private static void push(ActiveCall call) {
    ACTIVE_CALLS.get().push(call);
  }

  private static void pop(ActiveCall expected) {
    ArrayDeque<ActiveCall> calls = ACTIVE_CALLS.get();
    if (!calls.isEmpty() && calls.peek() == expected) {
      calls.pop();
    } else {
      calls.remove(expected);
    }
    if (calls.isEmpty()) {
      ACTIVE_CALLS.remove();
    }
  }

  private static byte[] responsePrefix(Object response, int limit) {
    if (response == null || limit <= 0) {
      return new byte[0];
    }
    try {
      Object responseBody = ReflectiveHttpAccess.invokeNoArgs(response, "body");
      Object contentLength = ReflectiveHttpAccess.invokeNoArgs(responseBody, "contentLength");
      if (contentLength instanceof Number number && number.longValue() > limit) {
        return new byte[0];
      }
      Method peekBody = findCompatibleMethod(response.getClass(), "peekBody", (long) limit + 1L);
      Object body = invoke(peekBody, response, (long) limit + 1L);
      Object bytes = ReflectiveHttpAccess.invokeNoArgs(body, "bytes");
      return bytes instanceof byte[] value ? value : new byte[0];
    } catch (Throwable ignored) {
      return new byte[0];
    }
  }

  private static boolean requiresCapture(String method, String uri) {
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    if (HttpBodyPolicyEngine.hasCandidate(
        "OUTGOING", method, requestPath(uri), generation)) {
      return true;
    }
    return !configured(REQUEST_HEADERS_PROPERTY, generation).isBlank()
        || !configured(RESPONSE_HEADERS_PROPERTY, generation).isBlank();
  }

  private static String configured(String property, String generation) {
    return generation == null || generation.isBlank()
        ? System.getProperty(property, "")
        : System.getProperty(property + ".generation." + generation, "");
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

  private static Map<String, List<String>> headersWithBodyMetadata(
      Object message, Object body) {
    Map<String, List<String>> headers =
        new LinkedHashMap<>(ReflectiveHttpAccess.headers(message));
    addBodyHeader(headers, body, "contentType", "getContentType", "Content-Type");
    addBodyHeader(
        headers, body, "contentEncoding", "getContentEncoding", "Content-Encoding");
    return headers;
  }

  private static void addBodyHeader(
      Map<String, List<String>> headers,
      Object body,
      String accessor,
      String legacyAccessor,
      String name) {
    if (body == null || headers.keySet().stream().anyMatch(name::equalsIgnoreCase)) {
      return;
    }
    Object value = ReflectiveHttpAccess.invokeNoArgs(body, accessor);
    if (value == null) {
      value = ReflectiveHttpAccess.invokeNoArgs(body, legacyAccessor);
    }
    if (value != null) {
      headers.put(name, List.of(String.valueOf(value)));
    }
  }

  private static Object wrapSource(
      Object source, OutgoingHttpExchange.RequestAttempt attempt) {
    if (source == null || Proxy.isProxyClass(source.getClass())) {
      return source;
    }
    try {
      ClassLoader loader = source.getClass().getClassLoader();
      Class<?> sourceType = loadKnownType(loader, KnownApplicationType.SOURCE);
      if (!sourceType.isInstance(source)) {
        return source;
      }
      return Proxy.newProxyInstance(
          sourceType.getClassLoader(),
          new Class<?>[] {sourceType},
          new SourceCaptureHandler(source, attempt));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      return source;
    }
  }

  /**
   * Loads only application types selected by extension code.
   *
   * <p>The class name cannot originate in a policy, an HTTP exchange, or another application
   * value. Keeping the allowlist in a closed enum makes that trust boundary explicit and avoids
   * exposing a general-purpose reflective class loader.
   */
  private static Class<?> loadKnownType(ClassLoader loader, KnownApplicationType type)
      throws ClassNotFoundException {
    ClassLoader effectiveLoader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
    return effectiveLoader.loadClass(type.className);
  }

  private enum KnownApplicationType {
    INTERCEPTOR(CLIENT_NAMESPACE.concat("Interceptor")),
    BUFFERED_SINK(IO_NAMESPACE.concat("BufferedSink")),
    SOURCE(IO_NAMESPACE.concat("Source"));

    private final String className;

    KnownApplicationType(String className) {
      this.className = className;
    }
  }

  private static byte[] bytesBeforeInvocation(String name, Object[] arguments, int limit) {
    if (arguments == null || arguments.length == 0 || limit <= 0) {
      return null;
    }
    try {
      return switch (name) {
        case "write" -> writeArguments(arguments, limit);
        case "writeByte" ->
            copy(new byte[] {(byte) number(arguments[0]).intValue()}, 0, limit);
        case "writeShort" ->
            copy(shortBytes(number(arguments[0]).intValue(), false), 0, limit);
        case "writeShortLe" ->
            copy(shortBytes(number(arguments[0]).intValue(), true), 0, limit);
        case "writeInt" -> copy(intBytes(number(arguments[0]).intValue(), false), 0, limit);
        case "writeIntLe" -> copy(intBytes(number(arguments[0]).intValue(), true), 0, limit);
        case "writeLong" ->
            copy(longBytes(number(arguments[0]).longValue(), false), 0, limit);
        case "writeLongLe" ->
            copy(longBytes(number(arguments[0]).longValue(), true), 0, limit);
        case "writeDecimalLong" ->
            copy(
                String.valueOf(number(arguments[0]).longValue())
                    .getBytes(StandardCharsets.UTF_8),
                0,
                limit);
        case "writeHexadecimalUnsignedLong" ->
            copy(
                Long.toHexString(number(arguments[0]).longValue())
                    .getBytes(StandardCharsets.UTF_8),
                0,
                limit);
        case "writeUtf8" -> stringArguments(arguments, StandardCharsets.UTF_8, limit);
        case "writeUtf8CodePoint" ->
            encodeBounded(
                new String(Character.toChars(number(arguments[0]).intValue())),
                0,
                Character.charCount(number(arguments[0]).intValue()),
                StandardCharsets.UTF_8,
                limit);
        case "writeString" -> stringArguments(arguments, null, limit);
        default -> null;
      };
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] writeArguments(Object[] arguments, int limit)
      throws Throwable {
    Object source = arguments[0];
    if (source instanceof byte[] bytes) {
      int offset = arguments.length >= 3 ? number(arguments[1]).intValue() : 0;
      int count = arguments.length >= 3 ? number(arguments[2]).intValue() : bytes.length;
      return copy(bytes, offset, Math.min(count, limit));
    }
    if (source instanceof ByteBuffer buffer) {
      ByteBuffer duplicate = buffer.duplicate();
      byte[] bytes = new byte[Math.min(duplicate.remaining(), limit)];
      duplicate.get(bytes);
      return bytes;
    }
    if (source != null
        && source.getClass().getName().startsWith(IO_NAMESPACE)
        && source.getClass().getName().endsWith("ByteString")) {
      int size = ((Number) ReflectiveHttpAccess.invokeNoArgs(source, "size")).intValue();
      int offset = arguments.length >= 3 ? number(arguments[1]).intValue() : 0;
      int count = arguments.length >= 3 ? number(arguments[2]).intValue() : size - offset;
      int boundedCount = Math.max(0, Math.min(Math.min(count, limit), size - offset));
      if (boundedCount == 0) {
        return new byte[0];
      }
      Object slice =
          invoke(
              source.getClass().getMethod("substring", int.class, int.class),
              source,
              offset,
              offset + boundedCount);
      Object value = ReflectiveHttpAccess.invokeNoArgs(slice, "toByteArray");
      if (value instanceof byte[] bytes) {
        return bytes;
      }
    }
    if (source != null
        && source.getClass().getName().equals(IO_NAMESPACE.concat("Buffer"))
        && arguments.length >= 2) {
      return bufferSlice(source, 0, Math.min(number(arguments[1]).longValue(), limit));
    }
    return null;
  }

  private static byte[] stringArguments(
      Object[] arguments, Charset defaultCharset, int limit) {
    String value = String.valueOf(arguments[0]);
    int begin = 0;
    int end = value.length();
    Charset charset = defaultCharset;
    if (arguments.length >= 4) {
      begin = number(arguments[1]).intValue();
      end = number(arguments[2]).intValue();
      charset = (Charset) arguments[3];
    } else if (charset == null) {
      charset = (Charset) arguments[1];
    }
    return encodeBounded(value, begin, end, charset, limit);
  }

  private static byte[] encodeBounded(
      String value, int begin, int end, Charset charset, int limit) {
    if (limit <= 0 || begin < 0 || end < begin || end > value.length()) {
      return new byte[0];
    }
    CharsetEncoder encoder =
        charset
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    ByteBuffer encoded = ByteBuffer.allocate(limit);
    try {
      var encodedResult = encoder.encode(CharBuffer.wrap(value, begin, end), encoded, true);
      if (!encodedResult.isOverflow()) {
        encoder.flush(encoded);
      }
      encoded.flip();
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } finally {
      Arrays.fill(encoded.array(), (byte) 0);
    }
  }

  private static byte[] appendedBytes(Object buffer, long previousSize, long count) {
    try {
      return bufferSlice(buffer, previousSize, count);
    } catch (Throwable ignored) {
      return new byte[0];
    }
  }

  private static byte[] bufferSlice(Object buffer, long offset, long count) throws Throwable {
    if (count <= 0) {
      return new byte[0];
    }
    Class<?> bufferType = buffer.getClass();
    Object bounded = bufferType.getConstructor().newInstance();
    Method copyTo =
        bufferType.getMethod("copyTo", bufferType, long.class, long.class);
    invoke(copyTo, buffer, bounded, offset, count);
    Object bytes =
        invoke(findCompatibleMethod(bounded.getClass(), "readByteArray", count), bounded, count);
    return bytes instanceof byte[] value ? value : new byte[0];
  }

  private static long bufferSize(Object buffer) {
    Object size = ReflectiveHttpAccess.invokeNoArgs(buffer, "size");
    return size instanceof Number number ? number.longValue() : 0;
  }

  private static Number number(Object value) {
    if (value instanceof Number number) {
      return number;
    }
    throw new IllegalArgumentException("Expected a numeric argument");
  }

  private static byte[] copy(byte[] source, int offset, int count) {
    if (offset < 0 || count <= 0 || offset >= source.length) {
      return new byte[0];
    }
    int length = Math.min(count, source.length - offset);
    byte[] result = new byte[length];
    System.arraycopy(source, offset, result, 0, length);
    return result;
  }

  private static byte[] shortBytes(int value, boolean littleEndian) {
    return littleEndian
        ? new byte[] {(byte) value, (byte) (value >>> 8)}
        : new byte[] {(byte) (value >>> 8), (byte) value};
  }

  private static byte[] intBytes(int value, boolean littleEndian) {
    byte[] bytes = new byte[4];
    for (int index = 0; index < bytes.length; index++) {
      int target = littleEndian ? index : bytes.length - 1 - index;
      bytes[target] = (byte) ((value >>> (index * 8)) & 0xff);
    }
    return bytes;
  }

  private static byte[] longBytes(long value, boolean littleEndian) {
    byte[] bytes = new byte[8];
    for (int index = 0; index < bytes.length; index++) {
      int target = littleEndian ? index : bytes.length - 1 - index;
      bytes[target] = (byte) (value >>> (index * 8));
    }
    return bytes;
  }

  private static Method findCompatibleMethod(Class<?> type, String name, Object argument)
      throws NoSuchMethodException {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name)
          && method.getParameterCount() == 1
          && compatible(method.getParameterTypes()[0], argument)) {
        return method;
      }
    }
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (method.getName().equals(name)
            && method.getParameterCount() == 1
            && compatible(method.getParameterTypes()[0], argument)) {
          method.trySetAccessible();
          return method;
        }
      }
    }
    throw new NoSuchMethodException(type.getName() + '#' + name);
  }

  private static boolean compatible(Class<?> parameter, Object argument) {
    if (argument == null) {
      return !parameter.isPrimitive();
    }
    if (parameter.isInstance(argument)) {
      return true;
    }
    return (parameter == long.class && argument instanceof Long)
        || (parameter == int.class && argument instanceof Integer)
        || (parameter == boolean.class && argument instanceof Boolean);
  }

  private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
    try {
      if (!method.canAccess(target)) {
        method.trySetAccessible();
      }
      return method.invoke(target, arguments);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private static Object objectMethod(
      Object proxy, Method method, Object[] arguments, String description) {
    return switch (method.getName()) {
      case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> description;
      default -> throw new UnsupportedOperationException(method.toGenericString());
    };
  }

  private static final class ActiveCall {
    private final OutgoingHttpExchange exchange;
    private final Object requestBody;

    private ActiveCall(OutgoingHttpExchange exchange, Object requestBody) {
      this.exchange = exchange;
      this.requestBody = requestBody;
    }
  }

  /** Advice state carrying the original-compatible sink and its isolated capture attempt. */
  public static final class RequestWriteState {
    private final Object sink;
    private final OutgoingHttpExchange.RequestAttempt attempt;

    private RequestWriteState(Object sink, OutgoingHttpExchange.RequestAttempt attempt) {
      this.sink = sink;
      this.attempt = attempt;
    }

    public Object sink() {
      return sink;
    }
  }

  /** Mutable request carrier used by the Java Agent instrumenter for propagation. */
  public static final class OkHttpRequest {
    private Object carrier;
    private final String method;
    private final String uri;
    private final Map<String, List<String>> headers;

    private OkHttpRequest(
        Object carrier, String method, String uri, Map<String, List<String>> headers) {
      this.carrier = carrier;
      this.method = method;
      this.uri = uri;
      this.headers = headers;
    }

    public String getHeader(String name) {
      List<String> values = header(headers, name);
      return values.isEmpty() ? null : values.getFirst();
    }
  }

  private record OkHttpResponse(int status, Map<String, List<String>> headers) {}

  private static final class OkHttpAttributesGetter
      implements HttpClientAttributesGetter<OkHttpRequest, OkHttpResponse> {
    @Override
    public String getHttpRequestMethod(OkHttpRequest request) {
      return request.method;
    }

    @Override
    public List<String> getHttpRequestHeader(OkHttpRequest request, String name) {
      return header(request.headers, name);
    }

    @Override
    public Integer getHttpResponseStatusCode(
        OkHttpRequest request, OkHttpResponse response, Throwable error) {
      return response == null || response.status <= 0 ? null : response.status;
    }

    @Override
    public List<String> getHttpResponseHeader(
        OkHttpRequest request, OkHttpResponse response, String name) {
      return response == null ? List.of() : header(response.headers, name);
    }

    @Override
    public String getUrlFull(OkHttpRequest request) {
      return request.uri;
    }

    @Override
    public String getServerAddress(OkHttpRequest request) {
      URI uri = absoluteUri(request.uri);
      return uri == null ? null : uri.getHost();
    }

    @Override
    public Integer getServerPort(OkHttpRequest request) {
      URI uri = absoluteUri(request.uri);
      return uri == null || uri.getPort() < 0 ? null : uri.getPort();
    }
  }

  private enum RequestHeadersSetter implements TextMapSetter<OkHttpRequest> {
    INSTANCE;

    @Override
    public void set(OkHttpRequest request, String key, String value) {
      if (request == null || request.carrier == null || key == null || value == null) {
        return;
      }
      try {
        Object builder = ReflectiveHttpAccess.invokeNoArgs(request.carrier, "newBuilder");
        if (builder == null) {
          return;
        }
        ReflectiveHttpAccess.invoke(
            builder, "header", String.class, key, String.class, value);
        Object updated = ReflectiveHttpAccess.invokeNoArgs(builder, "build");
        if (updated != null) {
          request.carrier = updated;
        }
      } catch (Throwable ignored) {
        // An incompatible request shape keeps the application's original propagation behavior.
      }
    }
  }

  private static final class ApplicationInterceptorHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, arguments, "o11y outgoing application interceptor");
      }
      if (!"intercept".equals(method.getName())
          || arguments == null
          || arguments.length != 1) {
        throw new UnsupportedOperationException(method.toGenericString());
      }
      return intercept(arguments[0]);
    }

    private Object intercept(Object chain) throws Throwable {
      Object request = ReflectiveHttpAccess.invokeNoArgs(chain, "request");
      Object requestBody = ReflectiveHttpAccess.invokeNoArgs(request, "body");
      String requestMethod = ReflectiveHttpAccess.method(request);
      String requestUri = ReflectiveHttpAccess.uri(request);
      Map<String, List<String>> requestHeaders = headersWithBodyMetadata(request, requestBody);
      if (!requiresCapture(requestMethod, requestUri)) {
        return proceed(chain, request);
      }

      OkHttpRequest telemetryRequest =
          new OkHttpRequest(request, requestMethod, requestUri, requestHeaders);
      Context parentContext = Context.current();
      Context clientContext = parentContext;
      boolean started = INSTRUMENTER.shouldStart(parentContext, telemetryRequest);
      if (started) {
        clientContext = INSTRUMENTER.start(parentContext, telemetryRequest);
      }

      OkHttpResponse telemetryResponse = null;
      Throwable failure = null;
      Scope scope = clientContext.makeCurrent();
      try {
        Object response = capture(chain, telemetryRequest, requestBody, requestHeaders);
        telemetryResponse = responseSnapshot(response);
        return response;
      } catch (Throwable original) {
        failure = original;
        throw original;
      } finally {
        scope.close();
        if (started) {
          INSTRUMENTER.end(clientContext, telemetryRequest, telemetryResponse, failure);
        }
      }
    }

    private Object capture(
        Object chain,
        OkHttpRequest telemetryRequest,
        Object originalRequestBody,
        Map<String, List<String>> requestHeaders)
        throws Throwable {
      Object request = telemetryRequest.carrier;
      Object requestBody = ReflectiveHttpAccess.invokeNoArgs(request, "body");
      if (requestBody == null) {
        requestBody = originalRequestBody;
      }
      OutgoingHttpExchange exchange;
      try {
        exchange =
            OutgoingHttpExchange.start(
                telemetryRequest.method, telemetryRequest.uri, requestHeaders);
      } catch (Throwable ignored) {
        return proceed(chain, request);
      }
      if (!exchange.isOwner()) {
        return proceed(chain, request);
      }

      ActiveCall call = new ActiveCall(exchange, requestBody);
      push(call);
      Object response;
      try {
        response = proceed(chain, request);
      } catch (Throwable original) {
        exchange.abort();
        throw original;
      } finally {
        pop(call);
      }

      int responseStatus = ReflectiveHttpAccess.status(response);
      byte[] responseBytes =
          responsePrefix(response, exchange.responseCaptureLimit(responseStatus));
      try {
        exchange.complete(
            responseStatus,
            headersWithBodyMetadata(
                response, ReflectiveHttpAccess.invokeNoArgs(response, "body")),
            responseBytes);
      } catch (Throwable ignored) {
        exchange.abort();
      } finally {
        Arrays.fill(responseBytes, (byte) 0);
      }
      return response;
    }

    private static Object proceed(Object chain, Object request) throws Throwable {
      Method proceed = findCompatibleMethod(chain.getClass(), "proceed", request);
      return OkHttpClientBridge.invoke(proceed, chain, request);
    }
  }

  private static OkHttpResponse responseSnapshot(Object response) {
    if (response == null) {
      return null;
    }
    return new OkHttpResponse(
        ReflectiveHttpAccess.status(response),
        headersWithBodyMetadata(response, ReflectiveHttpAccess.invokeNoArgs(response, "body")));
  }

  private static List<String> header(Map<String, List<String>> headers, String name) {
    if (headers == null || name == null) {
      return List.of();
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return List.of();
  }

  private static URI absoluteUri(String value) {
    try {
      URI uri = URI.create(value);
      return uri.isAbsolute() ? uri : null;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static final class SinkCaptureHandler implements InvocationHandler {
    private final Object delegate;
    private final OutgoingHttpExchange.RequestAttempt attempt;

    private SinkCaptureHandler(
        Object delegate, OutgoingHttpExchange.RequestAttempt attempt) {
      this.delegate = delegate;
      this.attempt = attempt;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, arguments, "o11y capturing buffered sink");
      }

      Object[] actual = arguments == null ? new Object[0] : arguments.clone();
      if (actual.length > 0
          && method.getParameterTypes().length > 0
          && method.getParameterTypes()[0].getName().equals(IO_NAMESPACE.concat("Source"))) {
        actual[0] = wrapSource(actual[0], attempt);
      }
      byte[] captured =
          bytesBeforeInvocation(method.getName(), actual, attempt.remainingCaptureBytes());
      Object result;
      try {
        result = OkHttpClientBridge.invoke(method, delegate, actual);
        if (captured != null && captured.length > 0) {
          attempt.capture(captured, 0, captured.length);
        }
      } finally {
        if (captured != null) {
          Arrays.fill(captured, (byte) 0);
        }
      }
      if ("outputStream".equals(method.getName()) && result instanceof OutputStream output) {
        return attempt.capture(output);
      }
      return result == delegate ? proxy : result;
    }
  }

  private static final class SourceCaptureHandler implements InvocationHandler {
    private final Object delegate;
    private final OutgoingHttpExchange.RequestAttempt attempt;

    private SourceCaptureHandler(
        Object delegate, OutgoingHttpExchange.RequestAttempt attempt) {
      this.delegate = delegate;
      this.attempt = attempt;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, arguments, "o11y capturing source");
      }
      Object[] actual = arguments == null ? new Object[0] : arguments;
      long before =
          "read".equals(method.getName()) && actual.length > 0 ? bufferSize(actual[0]) : 0;
      Object result = OkHttpClientBridge.invoke(method, delegate, actual);
      if ("read".equals(method.getName())
          && actual.length > 0
          && result instanceof Number number
          && number.longValue() > 0) {
        byte[] captured =
            appendedBytes(
                actual[0],
                before,
                Math.min(number.longValue(), attempt.remainingCaptureBytes()));
        try {
          attempt.capture(captured, 0, captured.length);
        } finally {
          Arrays.fill(captured, (byte) 0);
        }
      }
      return result == delegate ? proxy : result;
    }
  }
}
