package dev.o11y.agent.http.client.spring;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.client.ReflectiveHttpAccess;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.bootstrap.internal.JavaagentHttpClientInstrumenters;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reflection-only bridge between injected agent helpers and Spring Web.
 *
 * <p>No Spring type is linked from this class, so loading the extension remains safe when Spring
 * Web is absent or when an unsupported version exposes a different API shape.
 */
public final class SpringRestClientBridge {
  private static final String INSTRUMENTATION_NAME = "dev.o11y.spring-restclient";
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String REQUEST_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.request.headers";
  private static final String RESPONSE_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.response.headers";
  private static final String OUTGOING_METRICS_PROPERTY =
      "o11y.dynamic.http.outgoing-metrics.enabled";
  private static final int MAX_HEADER_NAMES = 128;
  private static final int MAX_HEADER_VALUES_PER_NAME = 16;
  private static final int MAX_HEADER_NAME_CHARACTERS = 256;
  private static final int MAX_HEADER_VALUE_CHARACTERS = 4096;
  private static final String INTERCEPTOR_TYPE =
      "org.springframework.http.client.ClientHttpRequestInterceptor";
  private static final String RESPONSE_TYPE =
      "org.springframework.http.client.ClientHttpResponse";
  private static final SpringHttpAttributesGetter HTTP_ATTRIBUTES =
      new SpringHttpAttributesGetter();
  private static final Instrumenter<SpringRequest, SpringResponse> INSTRUMENTER =
      JavaagentHttpClientInstrumenters.create(
          INSTRUMENTATION_NAME, HTTP_ATTRIBUTES, RequestHeadersSetter.INSTANCE);

  private SpringRestClientBridge() {}

  /** Installs exactly one policy interceptor as the builder's final interceptor. */
  public static void install(Object builder) {
    if (builder == null) {
      return;
    }
    try {
      ClassLoader loader = builder.getClass().getClassLoader();
      Class<?> interceptorType = Class.forName(INTERCEPTOR_TYPE, false, loader);
      Object interceptor = createInterceptor(loader, interceptorType);
      Method registration = builder.getClass().getMethod("requestInterceptors", Consumer.class);
      if (!registration.canAccess(builder)) {
        registration.setAccessible(true);
      }
      Consumer<List<Object>> installLast =
          interceptors -> {
            Object installed = null;
            int installedCount = 0;
            for (Object candidate : interceptors) {
              if (isPolicyInterceptor(candidate)) {
                installedCount++;
                if (installed == null) {
                  installed = candidate;
                }
              }
            }
            if (installedCount == 1
                && !interceptors.isEmpty()
                && interceptors.getLast() == installed) {
              return;
            }
            interceptors.removeIf(SpringRestClientBridge::isPolicyInterceptor);
            interceptors.add(installed == null ? interceptor : installed);
          };
      registration.invoke(builder, installLast);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      // Unsupported Spring shape: do not modify the application client.
    }
  }

  /**
   * Returns a mutable interceptor list containing exactly one o11y policy interceptor.
   *
   * <p>This constructor-bound hook also covers {@code RestClient.create()} and does not depend on
   * mutating Spring's builder after it has already copied its state.
   */
  public static Object appendInterceptor(Object interceptors, ClassLoader loader) {
    try {
      Class<?> interceptorType = Class.forName(INTERCEPTOR_TYPE, false, loader);
      ArrayList<Object> result = new ArrayList<>();
      Object installed = null;
      if (interceptors instanceof Collection<?> existing) {
        for (Object candidate : existing) {
          if (isPolicyInterceptor(candidate)) {
            if (installed == null) {
              installed = candidate;
            }
          } else {
            result.add(candidate);
          }
        }
      }
      result.add(installed == null ? createInterceptor(loader, interceptorType) : installed);
      return result;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      return interceptors;
    }
  }

  private static Object createInterceptor(ClassLoader loader, Class<?> interceptorType)
      throws ReflectiveOperationException {
    Class<?> responseType = Class.forName(RESPONSE_TYPE, false, loader);
    Method intercept = interceptorMethod(interceptorType);
    Method execute = executionMethod(intercept.getParameterTypes()[2]);
    return Proxy.newProxyInstance(
        loader,
        new Class<?>[] {interceptorType},
        new RequestInterceptorHandler(responseType, execute));
  }

  private static boolean isPolicyInterceptor(Object candidate) {
    if (candidate == null || !Proxy.isProxyClass(candidate.getClass())) {
      return false;
    }
    try {
      return Proxy.getInvocationHandler(candidate) instanceof RequestInterceptorHandler;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  static final class RequestInterceptorHandler implements InvocationHandler {
    private final Class<?> responseType;
    private final Method execute;

    private RequestInterceptorHandler(Class<?> responseType, Method execute) {
      this.responseType = responseType;
      this.execute = execute;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, arguments, "o11y Spring RestClient interceptor");
      }
      if (method.isDefault()) {
        return InvocationHandler.invokeDefault(
            proxy, method, arguments == null ? new Object[0] : arguments);
      }
      if (!"intercept".equals(method.getName())
          || arguments == null
          || arguments.length != 3
          || !(arguments[1] instanceof byte[] requestBody)) {
        throw new UnsupportedOperationException(method.toGenericString());
      }

      Object request = arguments[0];
      Object execution = arguments[2];
      String requestMethod = ReflectiveHttpAccess.method(request);
      String requestUri = ReflectiveHttpAccess.uri(request);
      Map<String, List<String>> requestHeaders = springHeaders(request);
      if (!requiresCapture(requestMethod, requestUri)) {
        return execute(execution, request, requestBody);
      }

      SpringRequest telemetryRequest =
          new SpringRequest(request, requestMethod, requestUri, requestHeaders);
      Context parentContext = Context.current();
      Context clientContext = parentContext;
      boolean started = INSTRUMENTER.shouldStart(parentContext, telemetryRequest);
      if (started) {
        clientContext = INSTRUMENTER.start(parentContext, telemetryRequest);
      }

      SpringResponse telemetryResponse = null;
      Throwable failure = null;
      try {
        Scope scope = clientContext.makeCurrent();
        try {
          OutgoingHttpExchange exchange;
          try {
            exchange =
                OutgoingHttpExchange.start(requestMethod, requestUri, requestHeaders);
          } catch (Throwable ignoredStartFailure) {
            return execute(execution, request, requestBody);
          }

          if (!exchange.isOwner()) {
            Object response = execute(execution, request, requestBody);
            telemetryResponse = responseSnapshot(response);
            return response;
          }

          try {
            exchange.captureRequest(requestBody);
            Object response = execute(execution, request, requestBody);
            CaptureResult captured = captureResponse(exchange, response);
            telemetryResponse = captured.telemetryResponse();
            return captured.response();
          } catch (Throwable original) {
            exchange.fail(original);
            throw original;
          }
        } finally {
          scope.close();
        }
      } catch (Throwable original) {
        failure = original;
        throw original;
      } finally {
        if (started) {
          INSTRUMENTER.end(clientContext, telemetryRequest, telemetryResponse, failure);
        }
      }
    }

    private CaptureResult captureResponse(OutgoingHttpExchange exchange, Object response) {
      if (response == null) {
        completeSafely(exchange, 0, Map.of(), new byte[0]);
        return new CaptureResult(null, null);
      }

      int status = ReflectiveHttpAccess.status(response);
      Map<String, List<String>> headers = springHeaders(response);
      SpringResponse telemetryResponse = new SpringResponse(status, headers);
      int responseLimit = exchange.responseCaptureLimit(status);
      if (responseLimit <= 0) {
        completeSafely(exchange, status, headers, new byte[0]);
        return new CaptureResult(response, telemetryResponse);
      }

      Object body = ReflectiveHttpAccess.invokeNoArgs(response, "getBody");
      if (!(body instanceof InputStream input)) {
        completeSafely(exchange, status, headers, new byte[0]);
        return new CaptureResult(response, telemetryResponse);
      }

      BoundedRecordingInputStream recording =
          new BoundedRecordingInputStream(input, responseLimit + 1);
      ResponseHandler handler = new ResponseHandler(response, input);
      Object replayingResponse;
      try {
        replayingResponse =
            Proxy.newProxyInstance(
                responseType.getClassLoader(), new Class<?>[] {responseType}, handler);
      } catch (Throwable ignoredProxyFailure) {
        // Proxy construction happens before the first probe read, so the original response is
        // still untouched and is safe to return on unsupported runtimes.
        exchange.abort();
        return new CaptureResult(response, telemetryResponse);
      }
      try {
        OutgoingHttpExchange.ReplayBody replay =
            OutgoingHttpExchange.readAndReplay(recording, responseLimit);
        handler.setReplay(replay.stream());
        completeSafely(exchange, status, headers, replay.captured());
        return new CaptureResult(replayingResponse, telemetryResponse);
      } catch (IOException failure) {
        close(input, failure);
        handler.setReplay(new FaultingInputStream(recording.captured(), failure));
        exchange.fail(status, headers, failure);
        return new CaptureResult(replayingResponse, telemetryResponse);
      } catch (Throwable failure) {
        close(input, failure);
        handler.setReplay(new FaultingInputStream(recording.captured(), failure));
        exchange.fail(status, headers, failure);
        return new CaptureResult(replayingResponse, telemetryResponse);
      }
    }

    private static void close(InputStream input, Throwable failure) {
      try {
        input.close();
      } catch (Throwable closeFailure) {
        if (closeFailure != failure) {
          failure.addSuppressed(closeFailure);
        }
      }
    }

    private Object execute(Object executionTarget, Object request, byte[] body) throws Throwable {
      try {
        return execute.invoke(executionTarget, request, body);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }

    private static void completeSafely(
        OutgoingHttpExchange exchange,
        int status,
        Map<String, List<String>> headers,
        byte[] body) {
      try {
        exchange.complete(status, headers, body);
      } catch (Throwable ignored) {
        // Dynamic capture must never change the RestClient result.
      }
    }
  }

  /** Request metadata used by an OTel client Instrumenter around the whole RestClient exchange. */
  public record SpringRequest(
      Object carrier, String method, String uri, Map<String, List<String>> headers) {
    public SpringRequest {
      headers = immutableHeaders(headers);
    }

    /** Public, case-insensitive view used by dynamic HTTP metric/span customizers. */
    public String getHeader(String name) {
      List<String> values = header(headers, name);
      return values.isEmpty() ? null : values.getFirst();
    }
  }

  /** Response metadata available before RestClient converts and closes the response body. */
  record SpringResponse(int status, Map<String, List<String>> headers) {
    SpringResponse {
      headers = immutableHeaders(headers);
    }
  }

  private record CaptureResult(Object response, SpringResponse telemetryResponse) {}

  /**
   * Provides standard HTTP client semantic conventions and, importantly, the HTTP-client span key
   * used by the Java Agent's semantic-convention suppression strategy. The lower JDK transport span
   * is therefore suppressed while this span is active instead of being duplicated.
   */
  static final class SpringHttpAttributesGetter
      implements HttpClientAttributesGetter<SpringRequest, SpringResponse> {
    @Override
    public String getHttpRequestMethod(SpringRequest request) {
      return request.method();
    }

    @Override
    public List<String> getHttpRequestHeader(SpringRequest request, String name) {
      return header(request.headers(), name);
    }

    @Override
    public Integer getHttpResponseStatusCode(
        SpringRequest request, SpringResponse response, Throwable error) {
      return response == null || response.status() <= 0 ? null : response.status();
    }

    @Override
    public List<String> getHttpResponseHeader(
        SpringRequest request, SpringResponse response, String name) {
      return response == null ? List.of() : header(response.headers(), name);
    }

    @Override
    public String getUrlFull(SpringRequest request) {
      return request.uri();
    }

    @Override
    public String getServerAddress(SpringRequest request) {
      URI uri = absoluteUri(request.uri());
      return uri == null ? null : uri.getHost();
    }

    @Override
    public Integer getServerPort(SpringRequest request) {
      URI uri = absoluteUri(request.uri());
      if (uri == null) {
        return null;
      }
      int port = uri.getPort();
      return port < 0 ? null : port;
    }
  }

  /** Injects the span context before the request is delegated to the selected Spring transport. */
  enum RequestHeadersSetter implements TextMapSetter<SpringRequest> {
    INSTANCE;

    @Override
    public void set(SpringRequest carrier, String key, String value) {
      if (carrier == null || carrier.carrier() == null || key == null || value == null) {
        return;
      }
      Object headers = ReflectiveHttpAccess.invokeNoArgs(carrier.carrier(), "getHeaders");
      if (headers == null) {
        return;
      }
      try {
        Method setter = headers.getClass().getMethod("set", String.class, String.class);
        if (!setter.canAccess(headers)) {
          setter.setAccessible(true);
        }
        setter.invoke(headers, key, value);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // A read-only request shape keeps its original propagation behavior; capture remains safe.
      }
    }
  }

  static final class ResponseHandler implements InvocationHandler {
    private final Object delegate;
    private volatile InputStream replay;

    private ResponseHandler(Object delegate, InputStream replay) {
      this.delegate = delegate;
      this.replay = replay;
    }

    private void setReplay(InputStream replay) {
      this.replay = replay;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return objectMethod(proxy, method, arguments, "o11y replaying ClientHttpResponse");
      }
      if ("getBody".equals(method.getName()) && method.getParameterCount() == 0) {
        return replay;
      }
      try {
        return method.invoke(delegate, arguments);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }

  /** Records the bytes consumed while the policy engine probes a bounded response prefix. */
  private static final class BoundedRecordingInputStream extends FilterInputStream {
    private final int limit;
    private final ByteArrayOutputStream captured;

    private BoundedRecordingInputStream(InputStream source, int limit) {
      super(source);
      this.limit = Math.max(0, limit);
      captured = new ByteArrayOutputStream(Math.min(this.limit, 8192));
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0 && captured.size() < limit) {
        captured.write(value);
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = super.read(bytes, offset, length);
      if (read > 0 && captured.size() < limit) {
        captured.write(bytes, offset, Math.min(read, limit - captured.size()));
      }
      return read;
    }

    private byte[] captured() {
      return captured.toByteArray();
    }
  }

  /** Replays every byte observed before propagating the exact transport read failure. */
  private static final class FaultingInputStream extends InputStream {
    private final ByteArrayInputStream prefix;
    private final Throwable failure;
    private boolean thrown;

    private FaultingInputStream(byte[] prefix, Throwable failure) {
      this.prefix = new ByteArrayInputStream(prefix);
      this.failure = failure;
    }

    @Override
    public int read() throws IOException {
      int value = prefix.read();
      if (value < 0) {
        fail();
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = prefix.read(bytes, offset, length);
      if (read < 0) {
        fail();
      }
      return read;
    }

    private void fail() throws IOException {
      if (!thrown) {
        thrown = true;
        if (failure instanceof IOException ioFailure) {
          throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
          throw runtimeFailure;
        }
        if (failure instanceof Error error) {
          throw error;
        }
        throw new IOException(failure);
      }
    }
  }

  private static Method interceptorMethod(Class<?> interceptorType) throws NoSuchMethodException {
    for (Method method : interceptorType.getMethods()) {
      if ("intercept".equals(method.getName())
          && method.getParameterCount() == 3
          && method.getParameterTypes()[1] == byte[].class) {
        return method;
      }
    }
    throw new NoSuchMethodException(interceptorType.getName() + "#intercept");
  }

  private static Method executionMethod(Class<?> executionType) throws NoSuchMethodException {
    for (Method method : executionType.getMethods()) {
      if ("execute".equals(method.getName())
          && method.getParameterCount() == 2
          && method.getParameterTypes()[1] == byte[].class) {
        return method;
      }
    }
    throw new NoSuchMethodException(executionType.getName() + "#execute");
  }

  private static boolean requiresCapture(String method, String uri) {
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    if (HttpBodyPolicyEngine.hasCandidate(
        "OUTGOING", method, requestPath(uri), generation)) {
      return true;
    }
    if (!configured(REQUEST_HEADERS_PROPERTY, generation).isBlank()
        || !configured(RESPONSE_HEADERS_PROPERTY, generation).isBlank()) {
      return true;
    }
    return Boolean.parseBoolean(configured(OUTGOING_METRICS_PROPERTY, generation));
  }

  private static SpringResponse responseSnapshot(Object response) {
    return response == null
        ? null
        : new SpringResponse(
            ReflectiveHttpAccess.status(response), springHeaders(response));
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

  private static URI absoluteUri(String value) {
    try {
      URI uri = URI.create(value == null ? "" : value);
      return uri.isAbsolute() ? uri : null;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static List<String> header(Map<String, List<String>> headers, String name) {
    if (headers == null || headers.isEmpty() || name == null) {
      return List.of();
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() != null
          && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)
          && entry.getValue() != null) {
        return entry.getValue();
      }
    }
    return List.of();
  }

  private static Map<String, List<String>> immutableHeaders(
      Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          if (name != null && values != null) {
            result.put(name, List.copyOf(values));
          }
        });
    return Map.copyOf(result);
  }

  /** Supports Spring 7 HttpHeaders, which no longer implements {@link Map} directly. */
  private static Map<String, List<String>> springHeaders(Object message) {
    Map<String, List<String>> reflected = ReflectiveHttpAccess.headers(message);
    if (!reflected.isEmpty()) {
      return reflected;
    }
    Object headers = ReflectiveHttpAccess.invokeNoArgs(message, "getHeaders");
    Object multiValueMap = ReflectiveHttpAccess.invokeNoArgs(headers, "asMultiValueMap");
    if (multiValueMap instanceof Map<?, ?> map) {
      Map<String, List<String>> result = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (result.size() >= MAX_HEADER_NAMES) {
          break;
        }
        String name = boundedString(entry.getKey(), MAX_HEADER_NAME_CHARACTERS);
        List<String> values = stringValues(entry.getValue());
        if (name != null && !values.isEmpty()) {
          result.put(name, values);
        }
      }
      return Map.copyOf(result);
    }
    Object names = ReflectiveHttpAccess.invokeNoArgs(headers, "headerNames");
    if (!(names instanceof Collection<?> collection)) {
      return Map.of();
    }
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    for (Object name : collection) {
      if (result.size() >= MAX_HEADER_NAMES) {
        break;
      }
      String key = boundedString(name, MAX_HEADER_NAME_CHARACTERS);
      if (key == null) {
        continue;
      }
      try {
        List<String> values =
            stringValues(ReflectiveHttpAccess.invoke(headers, "get", String.class, key));
        if (!values.isEmpty()) {
          result.put(key, values);
        }
      } catch (Throwable ignored) {
        // Ignore only the incompatible header entry; the exchange remains observable.
      }
    }
    return Map.copyOf(result);
  }

  private static List<String> stringValues(Object values) {
    if (values instanceof Iterable<?> iterable) {
      ArrayList<String> result = new ArrayList<>();
      for (Object value : iterable) {
        if (result.size() >= MAX_HEADER_VALUES_PER_NAME) {
          break;
        }
        String safe = boundedString(value, MAX_HEADER_VALUE_CHARACTERS);
        if (safe != null) {
          result.add(safe);
        }
      }
      return List.copyOf(result);
    }
    String safe = boundedString(values, MAX_HEADER_VALUE_CHARACTERS);
    return safe == null ? List.of() : List.of(safe);
  }

  private static String boundedString(Object value, int maximumCharacters) {
    if (!(value instanceof String text)) {
      return null;
    }
    return text.substring(0, Math.min(text.length(), maximumCharacters));
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

}
