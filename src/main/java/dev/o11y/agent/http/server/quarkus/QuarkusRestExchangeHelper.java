package dev.o11y.agent.http.server.quarkus;

import dev.o11y.agent.http.HttpServerCompletionBridge;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import dev.o11y.agent.servlet.BoundedBodyCapture;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Runtime bridge for Quarkus REST. It has no static linkage to Quarkus or Vert.x APIs. */
public final class QuarkusRestExchangeHelper {
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String REQUEST_HEADERS_PROPERTY = "o11y.dynamic.request.headers";
  private static final String RESPONSE_HEADERS_PROPERTY = "o11y.dynamic.response.headers";
  private static final ConcurrentHashMap<Object, State> STATES = new ConcurrentHashMap<>();
  private static final java.util.logging.Logger DIAGNOSTIC_LOGGER =
      java.util.logging.Logger.getLogger(QuarkusRestExchangeHelper.class.getName());

  private QuarkusRestExchangeHelper() {}

  public static void start(Object exchange) {
    if (exchange == null || STATES.containsKey(exchange)) {
      return;
    }
    try {
      String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
      String method = stringValue(invoke(exchange, "getRequestMethod"));
      String path = stringValue(invoke(exchange, "getRequestPath"));
      if (method.isBlank() || path.isBlank()) {
        return;
      }
      List<String> directRequestHeaders = headerNames(REQUEST_HEADERS_PROPERTY, generation);
      List<String> directResponseHeaders = headerNames(RESPONSE_HEADERS_PROPERTY, generation);
      boolean eventCandidate =
          HttpBodyPolicyEngine.hasCandidate("INCOMING", method, path, generation);
      if (!eventCandidate && directRequestHeaders.isEmpty() && directResponseHeaders.isEmpty()) {
        return;
      }
      Context context = Context.current();
      HttpBodyPolicyEngine.CapturePlan plan =
          HttpBodyPolicyEngine.capturePlan("INCOMING", method, path, generation);
      List<String> eventRequestHeaderNames =
          HttpBodyPolicyEngine.requiredRequestHeaderNames(
              "INCOMING", method, path, generation);
      List<String> eventResponseHeaderNames =
          HttpBodyPolicyEngine.requiredResponseHeaderNames(
              "INCOMING", method, path, generation);
      List<String> eventQueryNames =
          HttpBodyPolicyEngine.requiredRequestQueryNames(
              "INCOMING", method, path, generation);
      State state =
          new State(
              generation,
              method,
              path,
              stringValue(invoke(exchange, "getRequestHeader", "Content-Type")),
              stringValue(invoke(exchange, "getRequestHeader", "Content-Encoding")),
              eventCandidate,
              selectedRequestHeaders(exchange, eventRequestHeaderNames),
              eventResponseHeaderNames,
              directRequestHeaders,
              directResponseHeaders,
              HttpBodyPolicyEngine.selectQueryParameters(
                  stringValue(invoke(exchange, "query")), eventQueryNames),
              plan.requestLimit() > 0 ? new BoundedBodyCapture(plan.requestLimit()) : null,
              plan.responseLimit() > 0 ? new BoundedBodyCapture(plan.responseLimit()) : null,
              context);
      if (STATES.putIfAbsent(exchange, state) == null) {
        if (!invokeVoid(exchange, "addCloseHandler", (Runnable) () -> abort(exchange))) {
          abort(exchange);
          return;
        }
        bindContext(exchange);
        publishTestState();
      }
    } catch (RuntimeException error) {
      abort(exchange);
      DIAGNOSTIC_LOGGER.log(Level.FINE, "o11y_quarkus_rest_capture=setup_skipped");
    }
  }

  /**
   * Binds the exchange to the first valid request context observed after Quarkus creates its
   * request context. The constructor itself runs before the standard OpenTelemetry server span is
   * made current, while entity decoding and response writing run inside that span.
   */
  public static void bindContext(Object exchange) {
    State state = STATES.get(exchange);
    if (state == null) {
      return;
    }
    Context current = Context.current();
    Span span = Span.fromContext(current);
    if (!span.getSpanContext().isValid()) {
      return;
    }
    state.context = current;
    HttpServerCompletionBridge.arm(
        current,
        (completionContext, failure) ->
            completeForServerSpan(exchange, failure, completionContext));
  }

  public static Object wrapInput(Object exchange, Object input) {
    State state = STATES.get(exchange);
    if (!(input instanceof InputStream stream)
        || state == null
        || state.requestCapture == null
        || !state.inputWrapped.compareAndSet(false, true)) {
      return input;
    }
    return new QuarkusCapturingInputStream(stream, state.requestCapture);
  }

  public static Object wrapOutput(Object exchange, Object output) {
    State state = STATES.get(exchange);
    if (!(output instanceof OutputStream stream)
        || state == null
        || state.responseCapture == null
        || !state.outputWrapped.compareAndSet(false, true)) {
      return output;
    }
    return new QuarkusCapturingOutputStream(exchange, stream, state.responseCapture);
  }

  public static Object wrapReadCallback(Object exchange, Object callback) {
    State state = STATES.get(exchange);
    if (callback == null || state == null || state.requestCapture == null) {
      return callback;
    }
    if (Proxy.isProxyClass(callback.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(callback);
      if (handler instanceof ReadCallbackHandler) {
        return callback;
      }
    }
    Class<?>[] interfaces = callback.getClass().getInterfaces();
    if (interfaces.length == 0) {
      return callback;
    }
    return Proxy.newProxyInstance(
        callback.getClass().getClassLoader(),
        interfaces,
        new ReadCallbackHandler(callback, state.requestCapture));
  }

  public static void captureResponseArguments(Object exchange, Object[] arguments) {
    State state = STATES.get(exchange);
    if (state == null || state.responseCapture == null || arguments == null) {
      return;
    }
    for (Object argument : arguments) {
      Optional<byte[]> candidate = bytes(argument);
      if (candidate.isPresent()) {
        byte[] bytes = candidate.orElseThrow();
        state.responseCapture.write(bytes, 0, bytes.length);
        wipe(bytes);
        return;
      }
    }
  }

  public static void complete(Object exchange, Throwable failure) {
    State state = STATES.get(exchange);
    if (state != null && failure != null) {
      state.responseFailure.compareAndSet(null, failure);
    }
  }

  private static Map<String, Object> completeForServerSpan(
      Object exchange, Throwable failure, Context completionContext) {
    State state = STATES.remove(exchange);
    if (state == null || !state.completed.compareAndSet(false, true)) {
      return Map.of();
    }
    publishTestState();
    byte[] requestBody = null;
    byte[] responseBody = null;
    Map<String, Object> spanAttributes = new LinkedHashMap<>();
    Map<String, Object> eventSpanAttributes = new LinkedHashMap<>();
    try {
      Context effectiveContext = completionContext == null ? state.context : completionContext;
      captureDirectHeaders(
          spanAttributes,
          exchange,
          state.directRequestHeaderNames,
          state.directResponseHeaderNames);
      if (!state.eventCandidate) {
        return Map.copyOf(spanAttributes);
      }
      requestBody = state.requestCapture == null ? new byte[0] : state.requestCapture.bytes();
      responseBody = state.responseCapture == null ? new byte[0] : state.responseCapture.bytes();
      Throwable effectiveFailure = failure == null ? state.responseFailure.get() : failure;
      int responseStatus = responseStatus(exchange, effectiveFailure);
      HttpBodyPolicyEngine.processCollectingSpanAttributes(
          "INCOMING",
          state.method,
          state.path,
          state.requestContentType,
          state.requestContentEncoding,
          requestBody,
          responseStatus,
          stringValue(invoke(exchange, "getResponseHeader", "Content-Type")),
          stringValue(invoke(exchange, "getResponseHeader", "Content-Encoding")),
          responseBody,
          state.requestHeaders,
          selectedResponseHeaders(exchange, state.eventResponseHeaderNames),
          state.requestQuery,
          HttpBodyPolicyEngine.selectRequestPathParameters(
              "INCOMING", state.method, state.path, Map.of(), state.generation),
          effectiveContext,
          state.generation,
          eventSpanAttributes);
      spanAttributes.putAll(eventSpanAttributes);
      return Map.copyOf(spanAttributes);
    } catch (RuntimeException error) {
      DIAGNOSTIC_LOGGER.log(Level.FINE, "o11y_quarkus_rest_capture=completion_skipped");
      return spanAttributes.isEmpty() ? Map.of() : Map.copyOf(spanAttributes);
    } finally {
      wipe(requestBody);
      wipe(responseBody);
      if (state.requestCapture != null) {
        state.requestCapture.clear();
      }
      if (state.responseCapture != null) {
        state.responseCapture.clear();
      }
    }
  }

  public static void resetResponse(Object exchange) {
    State state = STATES.get(exchange);
    if (state != null && state.responseCapture != null) {
      state.responseCapture.clear();
    }
  }

  public static void abort(Object exchange) {
    State state = STATES.remove(exchange);
    if (state == null || !state.completed.compareAndSet(false, true)) {
      return;
    }
    if (state.requestCapture != null) {
      state.requestCapture.clear();
    }
    if (state.responseCapture != null) {
      state.responseCapture.clear();
    }
    publishTestState();
  }

  static int activeExchangeCountForTest() {
    return STATES.size();
  }

  private static void publishTestState() {
    if (Boolean.getBoolean("o11y.quarkus.rest.smoke.diagnostics")) {
      System.setProperty(
          "o11y.quarkus.rest.active-exchanges", Integer.toString(STATES.size()));
    }
  }

  private static int responseStatus(Object exchange, Throwable failure) {
    if (failure != null) {
      return 500;
    }
    Object response = invoke(exchange, "vertxServerResponse");
    Object result = response == null ? null : invoke(response, "getStatusCode");
    return result instanceof Number number ? number.intValue() : 200;
  }

  private static void captureDirectHeaders(
      Map<String, Object> target,
      Object exchange,
      List<String> requestNames,
      List<String> responseNames) {
    for (String name : requestNames) {
      List<String> values = requestHeaderValues(exchange, name);
      if (!values.isEmpty()) {
        target.put("http.request.header." + attributeName(name), values);
      }
    }
    for (String name : responseNames) {
      List<String> values = responseHeaderValues(exchange, name);
      if (!values.isEmpty()) {
        target.put("http.response.header." + attributeName(name), values);
      }
    }
  }

  private static Map<String, List<String>> selectedRequestHeaders(
      Object exchange, List<String> names) {
    Map<String, List<String>> selected = new LinkedHashMap<>();
    for (String name : names) {
      List<String> values = requestHeaderValues(exchange, name);
      if (!values.isEmpty()) {
        selected.put(name.toLowerCase(Locale.ROOT), values);
      }
    }
    return Map.copyOf(selected);
  }

  private static Map<String, List<String>> selectedResponseHeaders(
      Object exchange, List<String> names) {
    Map<String, List<String>> selected = new LinkedHashMap<>();
    for (String name : names) {
      List<String> values = responseHeaderValues(exchange, name);
      if (!values.isEmpty()) {
        selected.put(name.toLowerCase(Locale.ROOT), values);
      }
    }
    return Map.copyOf(selected);
  }

  private static List<String> requestHeaderValues(Object exchange, String name) {
    Object reported = invoke(exchange, "getAllRequestHeaders", name);
    if (reported instanceof Iterable<?> values) {
      return boundedStrings(values);
    }
    String first = stringValue(invoke(exchange, "getRequestHeader", name));
    return first.isBlank() ? List.of() : List.of(truncate(first));
  }

  private static List<String> responseHeaderValues(Object exchange, String name) {
    Object reported = invoke(exchange, "getAllResponseHeaders");
    ArrayList<String> values = new ArrayList<>();
    if (reported instanceof Iterable<?> entries) {
      for (Object candidate : entries) {
        if (candidate instanceof Map.Entry<?, ?> entry
            && entry.getKey() != null
            && name.equalsIgnoreCase(String.valueOf(entry.getKey()))
            && entry.getValue() != null) {
          values.add(truncate(String.valueOf(entry.getValue())));
          if (values.size() == 4) {
            break;
          }
        }
      }
    }
    if (!values.isEmpty()) {
      return List.copyOf(values);
    }
    String first = stringValue(invoke(exchange, "getResponseHeader", name));
    return first.isBlank() ? List.of() : List.of(truncate(first));
  }

  private static List<String> boundedStrings(Iterable<?> values) {
    ArrayList<String> result = new ArrayList<>();
    for (Object value : values) {
      if (value != null) {
        result.add(truncate(String.valueOf(value)));
        if (result.size() == 4) {
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  private static List<String> headerNames(String property, String generation) {
    String source =
        generation == null || generation.isBlank()
            ? System.getProperty(property, "")
            : System.getProperty(property + ".generation." + generation, "");
    return Arrays.stream(source.split(","))
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isEmpty() && value.matches("[a-z0-9!#$%&'*+.^_`|~-]+"))
        .distinct()
        .limit(16)
        .toList();
  }

  private static Optional<byte[]> bytes(Object value) {
    if (value instanceof byte[] bytes) {
      return Optional.of(Arrays.copyOf(bytes, bytes.length));
    }
    if (value instanceof String text) {
      return Optional.of(text.getBytes(StandardCharsets.UTF_8));
    }
    if (value instanceof ByteBuffer buffer) {
      ByteBuffer duplicate = buffer.duplicate();
      byte[] bytes = new byte[duplicate.remaining()];
      duplicate.get(bytes);
      return Optional.of(bytes);
    }
    if (value != null && isType(value.getClass(), "io.vertx.core.buffer.Buffer")) {
      Object result = invoke(value, "getBytes");
      if (result instanceof byte[] bytes) {
        return Optional.of(Arrays.copyOf(bytes, bytes.length));
      }
    }
    return Optional.empty();
  }

  private static Object invoke(Object target, String methodName, Object... arguments) {
    if (target == null) {
      return null;
    }
    Method selected = null;
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(methodName)
          && method.getParameterCount() == arguments.length
          && parametersAccept(method.getParameterTypes(), arguments)) {
        selected = method;
        break;
      }
    }
    if (selected == null) {
      return null;
    }
    try {
      return selected.invoke(target, arguments);
    } catch (IllegalAccessException error) {
      return null;
    } catch (InvocationTargetException error) {
      return null;
    }
  }

  private static boolean invokeVoid(Object target, String methodName, Object... arguments) {
    if (target == null) {
      return false;
    }
    for (Method method : target.getClass().getMethods()) {
      if (!method.getName().equals(methodName)
          || method.getParameterCount() != arguments.length
          || !parametersAccept(method.getParameterTypes(), arguments)) {
        continue;
      }
      try {
        method.invoke(target, arguments);
        return true;
      } catch (IllegalAccessException error) {
        return false;
      } catch (InvocationTargetException error) {
        return false;
      }
    }
    return false;
  }

  private static boolean parametersAccept(Class<?>[] types, Object[] arguments) {
    for (int index = 0; index < types.length; index++) {
      if (arguments[index] != null && !types[index].isInstance(arguments[index])) {
        return false;
      }
    }
    return true;
  }

  private static boolean isType(Class<?> type, String name) {
    if (type == null) {
      return false;
    }
    if (name.equals(type.getName())) {
      return true;
    }
    for (Class<?> implemented : type.getInterfaces()) {
      if (isType(implemented, name)) {
        return true;
      }
    }
    return isType(type.getSuperclass(), name);
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String truncate(String value) {
    return value.substring(0, Math.min(value.length(), 256));
  }

  private static String attributeName(String header) {
    return header.replace('-', '_');
  }

  private static void wipe(byte[] body) {
    if (body != null) {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static final class ReadCallbackHandler implements InvocationHandler {
    private final Object delegate;
    private final BoundedBodyCapture capture;

    private ReadCallbackHandler(Object delegate, BoundedBodyCapture capture) {
      this.delegate = delegate;
      this.capture = capture;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if ("data".equals(method.getName()) && arguments != null && arguments.length == 1) {
        Optional<byte[]> candidate = bytes(arguments[0]);
        if (candidate.isPresent()) {
          byte[] bytes = candidate.orElseThrow();
          capture.write(bytes, 0, bytes.length);
          wipe(bytes);
        }
      }
      try {
        return method.invoke(delegate, arguments);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }

  private static final class State {
    private final String generation;
    private final String method;
    private final String path;
    private final String requestContentType;
    private final String requestContentEncoding;
    private final boolean eventCandidate;
    private final Map<String, List<String>> requestHeaders;
    private final List<String> eventResponseHeaderNames;
    private final List<String> directRequestHeaderNames;
    private final List<String> directResponseHeaderNames;
    private final Map<String, List<String>> requestQuery;
    private final BoundedBodyCapture requestCapture;
    private final BoundedBodyCapture responseCapture;
    private volatile Context context;
    private final AtomicBoolean inputWrapped = new AtomicBoolean();
    private final AtomicBoolean outputWrapped = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Throwable> responseFailure =
        new java.util.concurrent.atomic.AtomicReference<>();

    private State(
        String generation,
        String method,
        String path,
        String requestContentType,
        String requestContentEncoding,
        boolean eventCandidate,
        Map<String, List<String>> requestHeaders,
        List<String> eventResponseHeaderNames,
        List<String> directRequestHeaderNames,
        List<String> directResponseHeaderNames,
        Map<String, List<String>> requestQuery,
        BoundedBodyCapture requestCapture,
        BoundedBodyCapture responseCapture,
        Context context) {
      this.generation = generation;
      this.method = method;
      this.path = path;
      this.requestContentType = requestContentType;
      this.requestContentEncoding = requestContentEncoding;
      this.eventCandidate = eventCandidate;
      this.requestHeaders = requestHeaders;
      this.eventResponseHeaderNames = List.copyOf(eventResponseHeaderNames);
      this.directRequestHeaderNames = List.copyOf(directRequestHeaderNames);
      this.directResponseHeaderNames = List.copyOf(directResponseHeaderNames);
      this.requestQuery = requestQuery;
      this.requestCapture = requestCapture;
      this.responseCapture = responseCapture;
      this.context = context;
    }
  }
}
