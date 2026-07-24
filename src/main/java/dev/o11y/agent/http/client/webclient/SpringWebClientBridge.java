package dev.o11y.agent.http.client.webclient;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.javaagent.bootstrap.internal.JavaagentHttpClientInstrumenters;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactive, backpressure-preserving policy filter for Spring WebClient over Reactor Netty. */
public final class SpringWebClientBridge {
  private static final String INSTRUMENTATION_NAME = "dev.o11y.spring-webclient-policy";
  private static final WebClientAttributesGetter HTTP_ATTRIBUTES =
      new WebClientAttributesGetter();
  private static final Instrumenter<WebClientRequest, WebClientResponse> INSTRUMENTER =
      JavaagentHttpClientInstrumenters.create(
          INSTRUMENTATION_NAME, HTTP_ATTRIBUTES, RequestHeadersSetter.INSTANCE);

  private SpringWebClientBridge() {}

  public static List<String> helperClassNames() {
    String bridge = SpringWebClientBridge.class.getName();
    return List.of(
        bridge,
        bridge + "$PolicyFilter",
        bridge + "$State",
        bridge + "$CapturingRequest",
        bridge + "$BoundedResponse",
        bridge + "$WebClientRequest",
        bridge + "$WebClientResponse",
        bridge + "$WebClientAttributesGetter",
        bridge + "$RequestHeadersSetter");
  }

  /** Installs one outer logical filter without changing the relative order of user filters. */
  public static void install(Object builder) {
    if (builder == null) {
      return;
    }
    try {
      var method = builder.getClass().getMethod("filters", Consumer.class);
      if (!method.canAccess(builder)) {
        method.trySetAccessible();
      }
      Consumer<List<ExchangeFilterFunction>> installer =
          filters -> {
            ExchangeFilterFunction installed = null;
            for (ExchangeFilterFunction filter : filters) {
              if (filter instanceof PolicyFilter) {
                installed = filter;
                break;
              }
            }
            filters.removeIf(PolicyFilter.class::isInstance);
            // The policy filter must be outermost. Spring's standard OTel filter otherwise starts
            // and ends its CLIENT span at response headers, before a response body policy can add
            // its attributes. Starting here suppresses that duplicate span and keeps one CLIENT
            // span open until the application consumes the response body.
            filters.add(0, installed == null ? new PolicyFilter() : installed);
          };
      method.invoke(builder, installer);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      // Unsupported Spring shape: preserve builder behavior.
    }
  }

  private static final class PolicyFilter implements ExchangeFilterFunction {
    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
      String method = request.method().name();
      String uri = request.url().toString();
      if (!OutgoingHttpExchange.isCaptureRequired(method, uri)) {
        return next.exchange(request);
      }
      return Mono.defer(() -> exchange(request, next));
    }

    private Mono<ClientResponse> exchange(ClientRequest request, ExchangeFunction next) {
      WebClientRequest telemetryRequest = new WebClientRequest(request);
      Context parent = Context.current();
      boolean started = INSTRUMENTER.shouldStart(parent, telemetryRequest);
      Context clientContext = started ? INSTRUMENTER.start(parent, telemetryRequest) : parent;
      Scope scope = clientContext.makeCurrent();
      OutgoingHttpExchange exchange = null;
      try {
        ClientRequest propagated = telemetryRequest.build();
        exchange =
            OutgoingHttpExchange.start(
                propagated.method().name(),
                propagated.url().toString(),
                headers(propagated));
        if (!exchange.isOwner()) {
          if (started) {
            INSTRUMENTER.end(clientContext, telemetryRequest, null, null);
          }
          return next.exchange(propagated);
        }
        State state =
            new State(exchange, telemetryRequest, clientContext, started);
        ClientRequest instrumented = requestWithCapture(propagated, state);
        Mono<ClientResponse> response = next.exchange(instrumented);
        // Subscription/serialization and response consumption can move threads. The exchange
        // remains alive, while the initiating ThreadLocal deduplication claim cannot.
        exchange.detachOwner();
        return response
            .map(state::response)
            .doOnError(state::abort)
            .doOnCancel(() -> state.abort(new IllegalStateException("WebClient exchange cancelled")));
      } catch (Throwable failure) {
        if (exchange != null) {
          exchange.abort();
        }
        if (started) {
          INSTRUMENTER.end(clientContext, telemetryRequest, null, failure);
        }
        return Mono.error(failure);
      } finally {
        scope.close();
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ClientRequest requestWithCapture(ClientRequest request, State state) {
    BodyInserter original = request.body();
    BodyInserter capturing =
        (output, context) ->
            original.insert(
                new CapturingRequest((ClientHttpRequest) output, state), context);
    return ClientRequest.from(request).body(capturing).build();
  }

  private static final class CapturingRequest extends ClientHttpRequestDecorator {
    private final State state;

    private CapturingRequest(ClientHttpRequest delegate, State state) {
      super(delegate);
      this.state = state;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
      Publisher<? extends DataBuffer> captured =
          Flux.defer(
              () -> {
                OutgoingHttpExchange.RequestAttempt attempt =
                    state.exchange.beginRequestAttempt();
                return Flux.from(body)
                    .doOnNext(buffer -> capture(buffer, attempt))
                    .doOnComplete(attempt::commit)
                    .doOnError(ignored -> attempt.discard())
                    .doOnCancel(attempt::discard);
              });
      return super.writeWith(captured);
    }

    @Override
    public Mono<Void> writeAndFlushWith(
        Publisher<? extends Publisher<? extends DataBuffer>> body) {
      return Mono.defer(
          () -> {
            OutgoingHttpExchange.RequestAttempt attempt =
                state.exchange.beginRequestAttempt();
            Publisher<? extends Publisher<? extends DataBuffer>> captured =
                Flux.from(body)
                    .map(
                        publisher ->
                            Flux.from(publisher)
                                .doOnNext(buffer -> capture(buffer, attempt)));
            // The outer publisher can finish before its inner publishers are consumed. Commit only
            // after the delegate confirms that every group was written and flushed.
            return super.writeAndFlushWith(captured)
                .doOnSuccess(ignored -> attempt.commit())
                .doOnError(ignored -> attempt.discard())
                .doOnCancel(attempt::discard);
          });
    }

    private static void capture(
        DataBuffer buffer, OutgoingHttpExchange.RequestAttempt attempt) {
      byte[] bytes = prefix(buffer, attempt.remainingCaptureBytes());
      try {
        attempt.capture(bytes, 0, bytes.length);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    }
  }

  private static final class State {
    private final OutgoingHttpExchange exchange;
    private final WebClientRequest telemetryRequest;
    private final Context clientContext;
    private final boolean started;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile WebClientResponse telemetryResponse;
    private volatile BoundedResponse responseBody;

    private State(
        OutgoingHttpExchange exchange,
        WebClientRequest telemetryRequest,
        Context clientContext,
        boolean started) {
      this.exchange = exchange;
      this.telemetryRequest = telemetryRequest;
      this.clientContext = clientContext;
      this.started = started;
    }

    private ClientResponse response(ClientResponse response) {
      int status = response.statusCode().value();
      Map<String, List<String>> responseHeaders = headers(response);
      telemetryResponse = new WebClientResponse(status, responseHeaders);
      int limit = exchange.responseCaptureLimit(status);
      responseBody = new BoundedResponse(limit);
      if (limit <= 0) {
        complete();
        return response;
      }
      return response
          .mutate()
          .body(
              body ->
                  body.doOnNext(responseBody::capture)
                      .doOnComplete(this::complete)
                      .doOnError(this::abort)
                      .doOnCancel(
                          () ->
                              abort(
                                  new IllegalStateException(
                                      "WebClient response body cancelled"))))
          .build();
    }

    private void complete() {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      BoundedResponse body = responseBody == null ? new BoundedResponse(0) : responseBody;
      byte[] bytes = body.bytes();
      try {
        WebClientResponse response = telemetryResponse;
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
      BoundedResponse body = responseBody;
      if (body != null) {
        body.clear();
      }
      exchange.abort();
      end(failure);
    }

    private void end(Throwable failure) {
      if (started) {
        INSTRUMENTER.end(
            clientContext, telemetryRequest, telemetryResponse, failure);
      }
    }
  }

  private static final class BoundedResponse {
    private final byte[] bytes;
    private int size;

    private BoundedResponse(int limit) {
      int capacity = limit <= 0 ? 0 : (int) Math.min((long) limit + 1L, Integer.MAX_VALUE - 8L);
      bytes = new byte[capacity];
    }

    private synchronized void capture(DataBuffer buffer) {
      int remaining = bytes.length - size;
      if (remaining <= 0 || buffer == null) {
        return;
      }
      int length = Math.min(remaining, buffer.readableByteCount());
      buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(bytes), size, length);
      size += length;
    }

    private synchronized byte[] bytes() {
      return Arrays.copyOf(bytes, size);
    }

    private synchronized void clear() {
      Arrays.fill(bytes, (byte) 0);
      size = 0;
    }
  }

  private static final class WebClientRequest {
    private final ClientRequest original;
    private final ClientRequest.Builder builder;

    private WebClientRequest(ClientRequest original) {
      this.original = original;
      builder = ClientRequest.from(original);
    }

    private ClientRequest build() {
      return builder.build();
    }

    private String method() {
      return original.method().name();
    }

    private String uri() {
      return original.url().toString();
    }

    private Map<String, List<String>> headers() {
      return SpringWebClientBridge.headers(original);
    }
  }

  private record WebClientResponse(int status, Map<String, List<String>> headers) {}

  private static final class WebClientAttributesGetter
      implements HttpClientAttributesGetter<WebClientRequest, WebClientResponse> {
    @Override
    public String getHttpRequestMethod(WebClientRequest request) {
      return request.method();
    }

    @Override
    public List<String> getHttpRequestHeader(WebClientRequest request, String name) {
      return header(request.headers(), name);
    }

    @Override
    public Integer getHttpResponseStatusCode(
        WebClientRequest request, WebClientResponse response, Throwable error) {
      return response == null || response.status() <= 0 ? null : response.status();
    }

    @Override
    public List<String> getHttpResponseHeader(
        WebClientRequest request, WebClientResponse response, String name) {
      return response == null ? List.of() : header(response.headers(), name);
    }

    @Override
    public String getUrlFull(WebClientRequest request) {
      return request.uri();
    }

    @Override
    public String getServerAddress(WebClientRequest request) {
      URI uri = URI.create(request.uri());
      return uri.getHost();
    }

    @Override
    public Integer getServerPort(WebClientRequest request) {
      int port = URI.create(request.uri()).getPort();
      return port < 0 ? null : port;
    }
  }

  private enum RequestHeadersSetter implements TextMapSetter<WebClientRequest> {
    INSTANCE;

    @Override
    public void set(WebClientRequest request, String key, String value) {
      if (request != null && key != null && value != null) {
        request.builder.header(key, value);
      }
    }
  }

  private static Map<String, List<String>> headers(ClientRequest request) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    request.headers().forEach((name, values) -> headers.put(name, List.copyOf(values)));
    return headers;
  }

  private static Map<String, List<String>> headers(ClientResponse response) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    response
        .headers()
        .asHttpHeaders()
        .forEach((name, values) -> headers.put(name, List.copyOf(values)));
    return headers;
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

  private static byte[] prefix(DataBuffer buffer, int limit) {
    if (buffer == null || limit <= 0) {
      return new byte[0];
    }
    byte[] bytes = new byte[Math.min(limit, buffer.readableByteCount())];
    buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(bytes), 0, bytes.length);
    return bytes;
  }
}
