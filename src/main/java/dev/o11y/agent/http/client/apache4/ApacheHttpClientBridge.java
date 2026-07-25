package dev.o11y.agent.http.client.apache4;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.client.ReflectiveHttpAccess;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection-only bridge shared by Apache HttpClient 4 classic and 5 classic.
 *
 * <p>Both generations expose the same entity lifecycle through different binary namespaces. The
 * instrumentation modules remain separate, while this namespace-neutral implementation keeps the
 * safety-critical replay and restoration logic identical.
 */
public final class ApacheHttpClientBridge {
  private ApacheHttpClientBridge() {}

  public static State enter(Object target, Object request) {
    OutgoingHttpExchange exchange = null;
    State state = null;
    try {
      String uri = ReflectiveHttpAccess.uri(request);
      if (uri.isBlank() && target != null) {
        Object targetUri = ReflectiveHttpAccess.invokeNoArgs(target, "toURI");
        uri = targetUri == null ? "" : String.valueOf(targetUri);
      }
      Object entity =
          reusableRequestEntity(ReflectiveHttpAccess.invokeNoArgs(request, "getEntity"));
      exchange =
          OutgoingHttpExchange.start(
              ReflectiveHttpAccess.method(request),
              uri,
              headersWithEntityMetadata(request, entity));
      state = new State(exchange);
      if (exchange.isOwner() && exchange.requestCaptureLimit() > 0) {
        wrapRequestEntity(state, request, entity, exchange);
      }
      return state;
    } catch (Throwable ignored) {
      if (state != null) {
        state.restoreRequestEntity();
      }
      if (exchange != null) {
        exchange.abort();
      }
      return State.NOOP;
    }
  }

  public static void exit(State state, Object response, Throwable error) {
    boolean requestRestored = state == null || state.restoreRequestEntity();
    if (state == null || state.exchange == null || !state.exchange.isOwner()) {
      return;
    }
    if (!requestRestored) {
      state.exchange.abort();
      return;
    }
    if (error != null) {
      state.exchange.fail(error);
      return;
    }
    if (response == null) {
      state.exchange.abort();
      return;
    }
    try {
      int responseStatus = ReflectiveHttpAccess.status(response);
      byte[] responseBody =
          captureAndReplaceResponseEntity(
              response, state.exchange.responseCaptureLimit(responseStatus));
      if (responseBody == null) {
        // The bounded probe observed a real transport read failure. The replacement
        // entity replays the consumed prefix and that same failure to the application,
        // but an incomplete response must never confirm a telemetry event.
        state.exchange.abort();
        return;
      }
      state.exchange.complete(
          responseStatus,
          headersWithEntityMetadata(
              response, ReflectiveHttpAccess.invokeNoArgs(response, "getEntity")),
          responseBody);
    } catch (Throwable ignored) {
      // Dynamic capture must never change whether the application call succeeds.
      state.exchange.abort();
    }
  }

  private static void wrapRequestEntity(
      State state, Object request, Object entity, OutgoingHttpExchange exchange)
      throws Throwable {
    if (entity == null) {
      return;
    }
    Method setter = findOneArgumentMethod(request.getClass(), "setEntity");
    Class<?> entityType = setter.getParameterTypes()[0];
    if (!entityType.isInterface()) {
      return;
    }
    RequestEntityHandler handler = new RequestEntityHandler(entity, exchange);
    Object proxy =
        Proxy.newProxyInstance(
            entityType.getClassLoader(),
            new Class<?>[] {entityType},
            handler);
    // Track the original before invoking application code: a setter that assigns and then throws
    // must still be repairable by enter's failure path.
    state.trackRequestEntity(request, setter, entity, handler);
    invoke(setter, request, proxy);
  }

  private static Object reusableRequestEntity(Object entity) {
    Object current = entity;
    while (current != null && Proxy.isProxyClass(current.getClass())) {
      try {
        InvocationHandler handler = Proxy.getInvocationHandler(current);
        if (handler instanceof RequestEntityHandler requestHandler
            && requestHandler.isDetached()) {
          current = requestHandler.delegate;
          continue;
        }
      } catch (IllegalArgumentException ignored) {
        // Not one of this bridge's proxies.
      }
      break;
    }
    return current;
  }

  private static byte[] captureAndReplaceResponseEntity(Object response, int limit)
      throws Throwable {
    if (limit <= 0) {
      return new byte[0];
    }
    Object entity = invokeNoArgsThrowing(response, "getEntity");
    if (entity == null) {
      return new byte[0];
    }

    Method setter = findOneArgumentMethod(response.getClass(), "setEntity");
    Class<?> entityType = setter.getParameterTypes()[0];
    if (!entityType.isInterface()) {
      return new byte[0];
    }

    // Install a transparent holder before the first body read. If proxy generation or the setter
    // fails, the original entity has not been advanced; if a setter assigns and then throws, the
    // holder still delegates to that untouched original entity.
    ResponseEntityHandler handler = new ResponseEntityHandler(entity);
    Object replacement =
        Proxy.newProxyInstance(
            entityType.getClassLoader(), new Class<?>[] {entityType}, handler);
    invoke(setter, response, replacement);

    boolean repeatable = Boolean.TRUE.equals(invokeNoArgsThrowing(entity, "isRepeatable"));

    Object content;
    try {
      content = invokeNoArgsThrowing(entity, "getContent");
    } catch (Throwable failure) {
      handler.setBuffered(new ReadResult(new byte[0], failure, false, true));
      return null;
    }
    if (!(content instanceof InputStream input)) {
      return new byte[0];
    }

    BoundedRecordingInputStream recording = new BoundedRecordingInputStream(input, limit + 1);
    byte[] captured;
    try {
      OutgoingHttpExchange.ReplayBody replay =
          OutgoingHttpExchange.readAndReplay(recording, limit);
      captured = replay.captured();
      if (captured.length <= limit) {
        Throwable closeFailure = close(input, null);
        handler.setBuffered(
            new ReadResult(captured, closeFailure, closeFailure != null, false));
        if (closeFailure != null) {
          captured = null;
        }
      } else {
        handler.setStreaming(
            new StreamingResponseEntityHandler(entity, replay.stream(), repeatable));
      }
    } catch (Throwable failure) {
      close(input, failure);
      captured = recording.captured();
      handler.setBuffered(new ReadResult(captured, failure, false, false));
      captured = null;
    }
    return captured;
  }

  private static Throwable close(InputStream input, Throwable failure) {
    try {
      input.close();
    } catch (Throwable closeFailure) {
      if (failure == null) {
        return closeFailure;
      }
      if (closeFailure != failure) {
        failure.addSuppressed(closeFailure);
      }
    }
    return failure;
  }

  private static Method findOneArgumentMethod(Class<?> type, String name)
      throws NoSuchMethodException {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == 1) {
        return method;
      }
    }
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == 1) {
          method.trySetAccessible();
          return method;
        }
      }
    }
    throw new NoSuchMethodException(type.getName() + '#' + name);
  }

  private static Object invokeNoArgsThrowing(Object target, String name) throws Throwable {
    Method method = target.getClass().getMethod(name);
    if (!method.canAccess(target)) {
      method.trySetAccessible();
    }
    return invoke(method, target);
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

  private static Map<String, List<String>> headersWithEntityMetadata(
      Object message, Object entity) {
    Map<String, List<String>> headers =
        new LinkedHashMap<>(ReflectiveHttpAccess.headers(message));
    addEntityHeader(headers, entity, "getContentType", "Content-Type");
    addEntityHeader(headers, entity, "getContentEncoding", "Content-Encoding");
    return headers;
  }

  private static void addEntityHeader(
      Map<String, List<String>> headers,
      Object entity,
      String accessor,
      String fallbackName) {
    if (entity == null
        || headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(fallbackName))) {
      return;
    }
    Object header = ReflectiveHttpAccess.invokeNoArgs(entity, accessor);
    if (header == null) {
      return;
    }
    Object reflectedName = ReflectiveHttpAccess.invokeNoArgs(header, "getName");
    Object reflectedValue = ReflectiveHttpAccess.invokeNoArgs(header, "getValue");
    String name = reflectedName == null ? fallbackName : String.valueOf(reflectedName);
    if (reflectedValue != null) {
      headers.put(name, List.of(String.valueOf(reflectedValue)));
    } else {
      // HttpCore 5 returns Content-Type and Content-Encoding directly as String values, while
      // HttpCore 4 returns Header objects.
      headers.put(name, List.of(String.valueOf(header)));
    }
  }

  public static final class State {
    private static final State NOOP = new State(null);

    private final OutgoingHttpExchange exchange;
    private Object request;
    private Method requestEntitySetter;
    private Object originalRequestEntity;
    private RequestEntityHandler requestEntityHandler;

    private State(OutgoingHttpExchange exchange) {
      this.exchange = exchange;
    }

    private void trackRequestEntity(
        Object request,
        Method setter,
        Object originalEntity,
        RequestEntityHandler handler) {
      this.request = request;
      this.requestEntitySetter = setter;
      this.originalRequestEntity = originalEntity;
      this.requestEntityHandler = handler;
    }

    private boolean restoreRequestEntity() {
      Object target = request;
      Method setter = requestEntitySetter;
      Object original = originalRequestEntity;
      RequestEntityHandler handler = requestEntityHandler;
      request = null;
      requestEntitySetter = null;
      originalRequestEntity = null;
      requestEntityHandler = null;
      if (handler != null) {
        handler.detach();
      }
      if (target == null || setter == null) {
        return true;
      }
      try {
        ApacheHttpClientBridge.invoke(setter, target, original);
        return true;
      } catch (Throwable ignored) {
        // The proxy is detached above, so even a hostile setter cannot retain this exchange.
        return false;
      }
    }

    boolean isOwner() {
      return exchange != null && exchange.isOwner();
    }
  }

  private static final class RequestEntityHandler implements InvocationHandler {
    private final Object delegate;
    private volatile OutgoingHttpExchange exchange;

    private RequestEntityHandler(Object delegate, OutgoingHttpExchange exchange) {
      this.delegate = delegate;
      this.exchange = exchange;
    }

    private void detach() {
      exchange = null;
    }

    private boolean isDetached() {
      return exchange == null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if (method.getName().equals("writeTo")
          && arguments != null
          && arguments.length == 1
          && arguments[0] instanceof OutputStream output) {
        OutgoingHttpExchange currentExchange = exchange;
        if (currentExchange == null) {
          return ApacheHttpClientBridge.invoke(method, delegate, output);
        }
        OutgoingHttpExchange.RequestAttempt attempt;
        OutputStream target;
        try {
          attempt = currentExchange.beginRequestAttempt();
          target = attempt.capture(output);
        } catch (Throwable ignoredCaptureFailure) {
          currentExchange.abort();
          return ApacheHttpClientBridge.invoke(method, delegate, output);
        }
        Object result;
        try {
          result = ApacheHttpClientBridge.invoke(method, delegate, target);
        } catch (Throwable original) {
          try {
            attempt.discard();
          } catch (Throwable ignoredDiscardFailure) {
            // Preserve the exact Apache/application serialization failure.
          }
          throw original;
        }
        try {
          attempt.commit();
        } catch (Throwable ignoredCommitFailure) {
          try {
            attempt.discard();
          } catch (Throwable ignoredDiscardFailure) {
            // The exchange is aborted below; capture cleanup cannot affect the request.
          }
          currentExchange.abort();
        }
        return result;
      }
      return ApacheHttpClientBridge.invoke(
          method, delegate, arguments == null ? new Object[0] : arguments);
    }
  }

  private static final class ResponseEntityHandler implements InvocationHandler {
    private final Object delegate;
    private final AtomicBoolean accessFailurePending = new AtomicBoolean();
    private volatile Object body;

    private ResponseEntityHandler(Object delegate) {
      this.delegate = delegate;
    }

    private void setBuffered(ReadResult result) {
      body = result;
      accessFailurePending.set(result.failureOnAccess);
    }

    private void setStreaming(StreamingResponseEntityHandler streaming) {
      body = streaming;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      Object current = body;
      if (current instanceof StreamingResponseEntityHandler streaming) {
        return streaming.invoke(proxy, method, arguments);
      }
      if (!(current instanceof ReadResult result)) {
        return ApacheHttpClientBridge.invoke(
            method, delegate, arguments == null ? new Object[0] : arguments);
      }
      if (result.failureOnAccess) {
        if ("getContent".equals(method.getName())
            && accessFailurePending.compareAndSet(true, false)) {
          throw result.failure;
        }
        return ApacheHttpClientBridge.invoke(
            method, delegate, arguments == null ? new Object[0] : arguments);
      }
      return switch (method.getName()) {
        case "getContent" ->
            result.failure == null
                ? new ByteArrayInputStream(result.bytes)
                : new FaultingInputStream(
                    result.bytes, result.failure, result.failureOnClose);
        case "writeTo" -> {
          OutputStream output = (OutputStream) arguments[0];
          output.write(result.bytes);
          if (result.failure != null) {
            throw result.failure;
          }
          yield null;
        }
        case "isRepeatable" -> result.failure == null;
        case "isStreaming" -> false;
        case "consumeContent" -> {
          if (result.failure != null) {
            throw result.failure;
          }
          yield null;
        }
        default ->
            ApacheHttpClientBridge.invoke(
                method, delegate, arguments == null ? new Object[0] : arguments);
      };
    }
  }

  /** Replays a bounded prefix before continuing on the original streaming entity. */
  private static final class StreamingResponseEntityHandler implements InvocationHandler {
    private final Object delegate;
    private final InputStream replay;
    private final boolean repeatable;
    private final AtomicBoolean firstAccess = new AtomicBoolean();

    private StreamingResponseEntityHandler(
        Object delegate, InputStream replay, boolean repeatable) {
      this.delegate = delegate;
      this.replay = replay;
      this.repeatable = repeatable;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      return switch (method.getName()) {
        case "getContent" ->
            firstAccess.compareAndSet(false, true) ? replay : repeatedContent(method);
        case "writeTo" -> {
          if (firstAccess.compareAndSet(false, true) || !repeatable) {
            Throwable transferFailure = null;
            try {
              replay.transferTo((OutputStream) arguments[0]);
            } catch (Throwable failure) {
              transferFailure = failure;
              throw failure;
            } finally {
              try {
                replay.close();
              } catch (Throwable closeFailure) {
                if (transferFailure == null) {
                  throw closeFailure;
                }
                if (closeFailure != transferFailure) {
                  transferFailure.addSuppressed(closeFailure);
                }
              }
            }
            yield null;
          }
          yield ApacheHttpClientBridge.invoke(method, delegate, arguments);
        }
        case "consumeContent" -> {
          if (firstAccess.compareAndSet(false, true) || !repeatable) {
            replay.close();
            yield null;
          }
          yield ApacheHttpClientBridge.invoke(method, delegate);
        }
        default ->
            ApacheHttpClientBridge.invoke(
                method, delegate, arguments == null ? new Object[0] : arguments);
      };
    }

    private Object repeatedContent(Method method) throws Throwable {
      return repeatable ? ApacheHttpClientBridge.invoke(method, delegate) : replay;
    }
  }

  /** Records only the prefix needed to reconstruct a stream if bounded probing fails. */
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

  private static final class FaultingInputStream extends InputStream {
    private final ByteArrayInputStream delegate;
    private final Throwable failure;
    private final boolean failureOnClose;
    private boolean thrown;

    private FaultingInputStream(byte[] bytes, Throwable failure, boolean failureOnClose) {
      delegate = new ByteArrayInputStream(bytes);
      this.failure = failure;
      this.failureOnClose = failureOnClose;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value < 0 && !failureOnClose) {
        fail();
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = delegate.read(bytes, offset, length);
      if (read < 0 && !failureOnClose) {
        fail();
      }
      return read;
    }

    @Override
    public void close() throws IOException {
      if (failureOnClose) {
        fail();
      }
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

  private record ReadResult(
      byte[] bytes, Throwable failure, boolean failureOnClose, boolean failureOnAccess) {}
}
