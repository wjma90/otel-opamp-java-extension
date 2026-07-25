package dev.o11y.agent.http.client.jdk;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.bootstrap.internal.JavaagentHttpClientInstrumenters;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded policy capture for the synchronous and asynchronous JDK {@link HttpClient}. */
public final class JdkHttpClientBridge {
  private static final String INSTRUMENTATION_NAME = "dev.o11y.jdk-httpclient-policy";
  private static final JdkAttributesGetter HTTP_ATTRIBUTES = new JdkAttributesGetter();
  private static final Instrumenter<JdkRequest, JdkResponse> INSTRUMENTER =
      JavaagentHttpClientInstrumenters.create(
          INSTRUMENTATION_NAME, HTTP_ATTRIBUTES, RequestHeadersSetter.INSTANCE);

  private JdkHttpClientBridge() {}

  public static List<String> helperClassNames() {
    String bridge = JdkHttpClientBridge.class.getName();
    return List.of(
        bridge,
        bridge + "$State",
        bridge + "$PolicyHttpRequest",
        bridge + "$CapturingBodyPublisher",
        bridge + "$CapturingRequestSubscriber",
        bridge + "$CapturingBodyHandler",
        bridge + "$CapturingBodySubscriber",
        bridge + "$BoundedResponse",
        bridge + "$JdkRequest",
        bridge + "$JdkResponse",
        bridge + "$JdkAttributesGetter",
        bridge + "$RequestHeadersSetter");
  }

  public static State enter(Object requestValue, Object handlerValue) {
    if (!(requestValue instanceof HttpRequest request)
        || !(handlerValue instanceof HttpResponse.BodyHandler<?> handler)
        || !OutgoingHttpExchange.isCaptureRequired(request.method(), request.uri().toString())) {
      return State.noop(requestValue, handlerValue);
    }

    PolicyHttpRequest policyRequest = new PolicyHttpRequest(request);
    JdkRequest telemetryRequest = new JdkRequest(policyRequest);
    Context parent = Context.current();
    boolean started = INSTRUMENTER.shouldStart(parent, telemetryRequest);
    Context clientContext = started ? INSTRUMENTER.start(parent, telemetryRequest) : parent;
    Scope scope = clientContext.makeCurrent();
    OutgoingHttpExchange exchange = null;
    try {
      exchange =
          OutgoingHttpExchange.start(
              policyRequest.method(),
              policyRequest.uri().toString(),
              policyRequest.headers().map());
      if (!exchange.isOwner()) {
        scope.close();
        if (started) {
          INSTRUMENTER.end(clientContext, telemetryRequest, null, null);
        }
        return State.noop(requestValue, handlerValue);
      }
      policyRequest.captureWith(exchange);
      State state =
          new State(
              policyRequest,
              null,
              exchange,
              telemetryRequest,
              clientContext,
              scope,
              started);
      state.responseHandler = new CapturingBodyHandler<>(handler, state);
      return state;
    } catch (Throwable ignored) {
      if (exchange != null) {
        exchange.abort();
      }
      scope.close();
      if (started) {
        INSTRUMENTER.end(clientContext, telemetryRequest, null, null);
      }
      return State.noop(requestValue, handlerValue);
    }
  }

  public static void exit(State state, Object result, Throwable failure) {
    if (state == null || state.noop) {
      return;
    }
    state.closeEntryScope();
    if (failure != null) {
      state.abort(failure);
    } else {
      // Completion can run on another thread, especially for sendAsync. The bounded exchange and
      // client span remain alive, but the initiating thread must no longer own deduplication.
      state.exchange.detachOwner();
      state.observeAsyncCompletion(result);
    }
  }

  public static final class State {
    private final Object request;
    private Object responseHandler;
    private final OutgoingHttpExchange exchange;
    private final JdkRequest telemetryRequest;
    private final Context clientContext;
    private Scope entryScope;
    private final boolean started;
    private final boolean noop;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile JdkResponse telemetryResponse;

    private State(
        Object request,
        Object responseHandler,
        OutgoingHttpExchange exchange,
        JdkRequest telemetryRequest,
        Context clientContext,
        Scope entryScope,
        boolean started) {
      this.request = request;
      this.responseHandler = responseHandler;
      this.exchange = exchange;
      this.telemetryRequest = telemetryRequest;
      this.clientContext = clientContext;
      this.entryScope = entryScope;
      this.started = started;
      this.noop = exchange == null;
    }

    private static State noop(Object request, Object handler) {
      return new State(request, handler, null, null, null, null, false);
    }

    public Object request() {
      return request;
    }

    public Object responseHandler() {
      return responseHandler;
    }

    private void responseInfo(HttpResponse.ResponseInfo info) {
      telemetryResponse = new JdkResponse(info.statusCode(), info.headers().map());
    }

    private void complete(BoundedResponse body) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      byte[] bytes = body.bytes();
      try {
        JdkResponse response = telemetryResponse;
        exchange.complete(
            response == null ? 0 : response.status(),
            response == null ? Map.of() : response.headers(),
            bytes);
      } finally {
        Arrays.fill(bytes, (byte) 0);
        body.clear();
        end(null);
      }
    }

    private void abort(Throwable failure) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      JdkResponse response = telemetryResponse;
      exchange.fail(
          response == null ? 0 : response.status(),
          response == null ? Map.of() : response.headers(),
          failure);
      end(failure);
    }

    private void observeAsyncCompletion(Object result) {
      if (!(result instanceof CompletionStage<?> stage)) {
        return;
      }
      try {
        stage.whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                abort(failure);
              }
            });
      } catch (Throwable failure) {
        abort(failure);
      }
    }

    boolean isFinished() {
      return finished.get();
    }

    private void end(Throwable failure) {
      // The entry scope belongs to the caller thread and is closed only by advice exit. Body
      // subscribers commonly finish on an HttpClient worker; closing that Scope here would leave
      // the caller thread's Context installed and incorrectly parent the next request.
      if (started) {
        INSTRUMENTER.end(
            clientContext, telemetryRequest, telemetryResponse, failure);
      }
    }

    private synchronized void closeEntryScope() {
      if (entryScope != null) {
        entryScope.close();
        entryScope = null;
      }
    }
  }

  private static final class PolicyHttpRequest extends HttpRequest {
    private final HttpRequest delegate;
    private final Map<String, List<String>> headers;
    private volatile OutgoingHttpExchange exchange;

    private PolicyHttpRequest(HttpRequest delegate) {
      this.delegate = delegate;
      headers = new LinkedHashMap<>();
      delegate.headers().map().forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
    }

    private void captureWith(OutgoingHttpExchange exchange) {
      this.exchange = exchange;
    }

    private void setHeader(String name, String value) {
      headers.put(name, new ArrayList<>(List.of(value)));
    }

    @Override
    public Optional<BodyPublisher> bodyPublisher() {
      Optional<BodyPublisher> body = delegate.bodyPublisher();
      OutgoingHttpExchange current = exchange;
      return current == null || current.requestCaptureLimit() <= 0
          ? body
          : body.map(publisher -> new CapturingBodyPublisher(publisher, current));
    }

    @Override
    public String method() {
      return delegate.method();
    }

    @Override
    public Optional<Duration> timeout() {
      return delegate.timeout();
    }

    @Override
    public boolean expectContinue() {
      return delegate.expectContinue();
    }

    @Override
    public URI uri() {
      return delegate.uri();
    }

    @Override
    public Optional<HttpClient.Version> version() {
      return delegate.version();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(headers, (name, value) -> true);
    }
  }

  private record CapturingBodyPublisher(
      HttpRequest.BodyPublisher delegate, OutgoingHttpExchange exchange)
      implements HttpRequest.BodyPublisher {
    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
      OutgoingHttpExchange.RequestAttempt attempt = exchange.beginRequestAttempt();
      delegate.subscribe(new CapturingRequestSubscriber(subscriber, attempt));
    }
  }

  private static final class CapturingRequestSubscriber implements Flow.Subscriber<ByteBuffer> {
    private final Flow.Subscriber<? super ByteBuffer> delegate;
    private final OutgoingHttpExchange.RequestAttempt attempt;

    private CapturingRequestSubscriber(
        Flow.Subscriber<? super ByteBuffer> delegate,
        OutgoingHttpExchange.RequestAttempt attempt) {
      this.delegate = delegate;
      this.attempt = attempt;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(ByteBuffer item) {
      byte[] bytes = prefix(item, attempt.remainingCaptureBytes());
      try {
        attempt.capture(bytes, 0, bytes.length);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
      delegate.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      attempt.discard();
      delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
      attempt.commit();
      delegate.onComplete();
    }
  }

  private record CapturingBodyHandler<T>(HttpResponse.BodyHandler<T> delegate, State state)
      implements HttpResponse.BodyHandler<T> {
    @Override
    public HttpResponse.BodySubscriber<T> apply(HttpResponse.ResponseInfo responseInfo) {
      state.responseInfo(responseInfo);
      HttpResponse.BodySubscriber<T> subscriber = delegate.apply(responseInfo);
      int limit = state.exchange.responseCaptureLimit(responseInfo.statusCode());
      BoundedResponse body = new BoundedResponse(limit);
      if (limit <= 0) {
        state.complete(body);
        return subscriber;
      }
      return new CapturingBodySubscriber<>(subscriber, state, body);
    }
  }

  private record CapturingBodySubscriber<T>(
      HttpResponse.BodySubscriber<T> delegate, State state, BoundedResponse body)
      implements HttpResponse.BodySubscriber<T> {
    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      for (ByteBuffer item : items) {
        body.capture(item);
      }
      delegate.onNext(items);
    }

    @Override
    public void onError(Throwable throwable) {
      body.clear();
      state.abort(throwable);
      delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
      state.complete(body);
      delegate.onComplete();
    }
  }

  private static final class BoundedResponse {
    private final byte[] buffer;
    private int size;

    private BoundedResponse(int limit) {
      int capacity = limit <= 0 ? 0 : (int) Math.min((long) limit + 1L, Integer.MAX_VALUE - 8L);
      buffer = new byte[capacity];
    }

    private synchronized void capture(ByteBuffer source) {
      int remaining = buffer.length - size;
      if (remaining <= 0 || source == null) {
        return;
      }
      ByteBuffer copy = source.duplicate();
      int length = Math.min(copy.remaining(), remaining);
      copy.get(buffer, size, length);
      size += length;
    }

    private synchronized byte[] bytes() {
      return Arrays.copyOf(buffer, size);
    }

    private synchronized void clear() {
      Arrays.fill(buffer, (byte) 0);
      size = 0;
    }
  }

  private record JdkRequest(PolicyHttpRequest carrier) {
    private String method() {
      return carrier.method();
    }

    private String uri() {
      return carrier.uri().toString();
    }

    private Map<String, List<String>> headers() {
      return carrier.headers().map();
    }
  }

  private record JdkResponse(int status, Map<String, List<String>> headers) {}

  private static final class JdkAttributesGetter
      implements HttpClientAttributesGetter<JdkRequest, JdkResponse> {
    @Override
    public String getHttpRequestMethod(JdkRequest request) {
      return request.method();
    }

    @Override
    public List<String> getHttpRequestHeader(JdkRequest request, String name) {
      return header(request.headers(), name);
    }

    @Override
    public Integer getHttpResponseStatusCode(
        JdkRequest request, JdkResponse response, Throwable error) {
      return response == null || response.status() <= 0 ? null : response.status();
    }

    @Override
    public List<String> getHttpResponseHeader(
        JdkRequest request, JdkResponse response, String name) {
      return response == null ? List.of() : header(response.headers(), name);
    }

    @Override
    public String getUrlFull(JdkRequest request) {
      return request.uri();
    }

    @Override
    public String getServerAddress(JdkRequest request) {
      return request.carrier().uri().getHost();
    }

    @Override
    public Integer getServerPort(JdkRequest request) {
      int port = request.carrier().uri().getPort();
      return port < 0 ? null : port;
    }
  }

  private enum RequestHeadersSetter implements TextMapSetter<JdkRequest> {
    INSTANCE;

    @Override
    public void set(JdkRequest request, String key, String value) {
      if (request != null && key != null && value != null) {
        request.carrier().setHeader(key, value);
      }
    }
  }

  private static List<String> header(Map<String, List<String>> headers, String name) {
    if (name == null) {
      return List.of();
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return List.of();
  }

  private static byte[] prefix(ByteBuffer source, int limit) {
    if (source == null || limit <= 0) {
      return new byte[0];
    }
    ByteBuffer copy = source.duplicate();
    byte[] bytes = new byte[Math.min(copy.remaining(), limit)];
    copy.get(bytes);
    return bytes;
  }
}
