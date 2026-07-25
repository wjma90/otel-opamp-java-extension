package dev.o11y.agent.servlet;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import dev.o11y.agent.http.runtime.HttpErrorType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.bootstrap.CallDepth;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Entry/exit bridge called by advice injected into Jakarta Servlet implementations. */
public final class ServletExchangeHelper {
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String REQUEST_HEADERS_PROPERTY = "o11y.dynamic.request.headers";
  private static final String RESPONSE_HEADERS_PROPERTY = "o11y.dynamic.response.headers";
  private static final String STATE_ATTRIBUTE =
      ServletExchangeHelper.class.getName() + ".state";
  private static final String SPRING_PATH_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
  private static final java.util.logging.Logger DIAGNOSTIC_LOGGER =
      java.util.logging.Logger.getLogger(ServletExchangeHelper.class.getName());

  private ServletExchangeHelper() {}

  /** Always returns a state so that call depth is decremented even when setup fails. */
  public static State enter(Object request, Object response) {
    CallDepth depth = CallDepth.forClass(ServletExchangeHelper.class);
    if (depth.getAndIncrement() > 0) {
      return State.nested();
    }
    try {
      if (!(request instanceof HttpServletRequest httpRequest)
          || !(response instanceof HttpServletResponse httpResponse)) {
        return State.outerNoop();
      }
      Object existing = httpRequest.getAttribute(STATE_ATTRIBUTE);
      if (existing instanceof State) {
        return State.reentry();
      }
      return State.start(httpRequest, httpResponse);
    } catch (RuntimeException error) {
      DIAGNOSTIC_LOGGER.log(Level.FINE, "o11y_servlet_capture=setup_skipped");
      return State.outerNoop();
    }
  }

  public static final class State {
    private final boolean owner;
    private final HttpServletRequest originalRequest;
    private final HttpServletResponse originalResponse;
    private final CapturingHttpServletRequest request;
    private final CapturingHttpServletResponse response;
    private final String generation;
    private final String method;
    private final String path;
    private final String requestContentType;
    private final String requestContentEncoding;
    private final boolean eventCandidate;
    private final Map<String, List<String>> eventRequestHeaders;
    private final List<String> eventResponseHeaderNames;
    private final Map<String, List<String>> eventRequestQuery;
    private final Context context;
    private final AtomicBoolean completed;

    private State(
        boolean owner,
        HttpServletRequest originalRequest,
        HttpServletResponse originalResponse,
        CapturingHttpServletRequest request,
        CapturingHttpServletResponse response,
        String generation,
        String method,
        String path,
        String requestContentType,
        String requestContentEncoding,
        boolean eventCandidate,
        Map<String, List<String>> eventRequestHeaders,
        List<String> eventResponseHeaderNames,
        Map<String, List<String>> eventRequestQuery,
        Context context,
        AtomicBoolean completed) {
      this.owner = owner;
      this.originalRequest = originalRequest;
      this.originalResponse = originalResponse;
      this.request = request;
      this.response = response;
      this.generation = generation;
      this.method = method;
      this.path = path;
      this.requestContentType = requestContentType;
      this.requestContentEncoding = requestContentEncoding;
      this.eventCandidate = eventCandidate;
      this.eventRequestHeaders = eventRequestHeaders;
      this.eventResponseHeaderNames = eventResponseHeaderNames;
      this.eventRequestQuery = eventRequestQuery;
      this.context = context;
      this.completed = completed;
    }

    static State start(HttpServletRequest request, HttpServletResponse response) {
      String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
      String method = request.getMethod();
      String path = request.getRequestURI();
      Context context = Context.current();
      captureRequestHeaders(Span.fromContext(context), request, generation);
      HttpBodyPolicyEngine.CapturePlan plan =
          HttpBodyPolicyEngine.capturePlan("INCOMING", method, path, generation);
      boolean eventCandidate =
          HttpBodyPolicyEngine.hasCandidate("INCOMING", method, path, generation);
      List<String> eventRequestHeaderNames =
          HttpBodyPolicyEngine.requiredRequestHeaderNames(
              "INCOMING", method, path, generation);
      List<String> eventResponseHeaderNames =
          HttpBodyPolicyEngine.requiredResponseHeaderNames(
              "INCOMING", method, path, generation);
      List<String> eventRequestQueryNames =
          HttpBodyPolicyEngine.requiredRequestQueryNames(
              "INCOMING", method, path, generation);
      CapturingHttpServletResponse wrappedResponse =
          plan.responseLimit() > 0
              ? new CapturingHttpServletResponse(response, plan.responseLimit())
              : null;
      CapturingHttpServletRequest wrappedRequest =
          plan.requestLimit() > 0
              ? new CapturingHttpServletRequest(request, wrappedResponse, plan.requestLimit())
              : null;
      State state =
          new State(
              true,
              request,
              response,
              wrappedRequest,
              wrappedResponse,
              generation,
              method,
              path,
              request.getContentType(),
              request.getHeader("Content-Encoding"),
              eventCandidate,
              selectedRequestHeaders(request, eventRequestHeaderNames),
              eventResponseHeaderNames,
              HttpBodyPolicyEngine.selectQueryParameters(
                  request.getQueryString(), eventRequestQueryNames),
              context,
              new AtomicBoolean());
      request.setAttribute(STATE_ATTRIBUTE, state);
      return state;
    }

    static State nested() {
      return new State(
          false,
          null,
          null,
          null,
          null,
          "",
          "",
          "",
          "",
          "",
          false,
          Map.of(),
          List.of(),
          Map.of(),
          Context.root(),
          null);
    }

    static State reentry() {
      return nested();
    }

    static State outerNoop() {
      return nested();
    }

    public Object request(Object fallback) {
      return owner && request != null ? request : fallback;
    }

    public Object response(Object fallback) {
      return owner && response != null ? response : fallback;
    }

    public void exit(Throwable failure) {
      try {
        if (!owner || originalRequest == null) {
          return;
        }
        CompletionOutcome outcome = CompletionOutcome.from(failure);
        if (outcome == CompletionOutcome.COMPLETED && originalRequest.isAsyncStarted()) {
          try {
            AsyncContext async = originalRequest.getAsyncContext();
            async.addListener(new ServletExchangeAsyncListener(this));
            return;
          } catch (IllegalStateException ignored) {
            // Async completed during registration; process the bytes already observed.
          }
        }
        complete(outcome, failure);
      } catch (RuntimeException error) {
        DIAGNOSTIC_LOGGER.log(Level.FINE, "o11y_servlet_capture=completion_skipped");
      } finally {
        CallDepth.forClass(ServletExchangeHelper.class).decrementAndGet();
      }
    }

    void complete() {
      complete(CompletionOutcome.COMPLETED, null);
    }

    void complete(CompletionOutcome outcome) {
      complete(outcome, null);
    }

    void complete(CompletionOutcome outcome, Throwable failure) {
      if (completed == null || !completed.compareAndSet(false, true)) {
        return;
      }
      byte[] requestBody = null;
      byte[] responseBody = null;
      try {
        captureResponseHeaders(Span.fromContext(context), originalResponse, generation);
        if (!eventCandidate) {
          return;
        }
        requestBody = request == null ? new byte[0] : request.capturedBody();
        responseBody = response == null ? new byte[0] : response.capturedBody();
        int responseStatus = outcome.responseStatus(originalResponse.getStatus());
        String errorType =
            outcome == CompletionOutcome.TIMED_OUT
                ? "timeout"
                : HttpErrorType.resolve("INCOMING", responseStatus, failure);
        HttpBodyPolicyEngine.processWithErrorType(
            "INCOMING",
            method,
            path,
            requestContentType,
            requestContentEncoding,
            requestBody,
            responseStatus,
            originalResponse.getContentType(),
            originalResponse.getHeader("Content-Encoding"),
            responseBody,
            eventRequestHeaders,
            selectedResponseHeaders(originalResponse, eventResponseHeaderNames),
            eventRequestQuery,
            selectedRequestPathParameters(originalRequest, method, path, generation),
            context,
            generation,
            errorType);
      } finally {
        wipe(requestBody);
        wipe(responseBody);
        if (request != null) {
          request.clearCapturedBody();
        }
        if (response != null) {
          response.clearCapturedBody();
        }
        originalRequest.removeAttribute(STATE_ATTRIBUTE);
      }
    }

    enum CompletionOutcome {
      COMPLETED(0),
      TIMED_OUT(504),
      FAILED(500);

      private final int fallbackStatus;

      CompletionOutcome(int fallbackStatus) {
        this.fallbackStatus = fallbackStatus;
      }

      static CompletionOutcome from(Throwable failure) {
        return failure == null ? COMPLETED : FAILED;
      }

      int responseStatus(int reportedStatus) {
        if (this == COMPLETED || reportedStatus >= 400 && reportedStatus <= 599) {
          return reportedStatus;
        }
        return fallbackStatus;
      }
    }
  }

  private static void wipe(byte[] body) {
    if (body != null) {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static void captureRequestHeaders(
      Span span, HttpServletRequest request, String generation) {
    if (!span.isRecording()) {
      return;
    }
    for (String name : headerNames(REQUEST_HEADERS_PROPERTY, generation)) {
      var values = request.getHeaders(name);
      ArrayList<String> captured = new ArrayList<>();
      while (values != null && values.hasMoreElements() && captured.size() < 4) {
        captured.add(truncate(values.nextElement(), 256));
      }
      if (!captured.isEmpty()) {
        span.setAttribute(
            AttributeKey.stringArrayKey("http.request.header." + attributeName(name)), captured);
      }
    }
  }

  private static void captureResponseHeaders(
      Span span, HttpServletResponse response, String generation) {
    if (!span.isRecording()) {
      return;
    }
    for (String name : headerNames(RESPONSE_HEADERS_PROPERTY, generation)) {
      List<String> values =
          response.getHeaders(name).stream().limit(4).map(value -> truncate(value, 256)).toList();
      if (!values.isEmpty()) {
        span.setAttribute(
            AttributeKey.stringArrayKey("http.response.header." + attributeName(name)), values);
      }
    }
  }

  private static Map<String, List<String>> selectedRequestHeaders(
      HttpServletRequest request, List<String> names) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String name : names) {
      var values = request.getHeaders(name);
      ArrayList<String> captured = new ArrayList<>();
      while (values != null && values.hasMoreElements() && captured.size() < 4) {
        String value = values.nextElement();
        if (value != null) {
          captured.add(truncate(value, 256));
        }
      }
      if (!captured.isEmpty()) {
        result.put(name, List.copyOf(captured));
      }
    }
    return Map.copyOf(result);
  }

  private static Map<String, List<String>> selectedResponseHeaders(
      HttpServletResponse response, List<String> names) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String name : names) {
      List<String> values =
          response.getHeaders(name).stream()
              .filter(value -> value != null)
              .limit(4)
              .map(value -> truncate(value, 256))
              .toList();
      if (!values.isEmpty()) {
        result.put(name, values);
      }
    }
    return Map.copyOf(result);
  }

  private static Map<String, List<String>> selectedRequestPathParameters(
      HttpServletRequest request, String method, String path, String generation) {
    Object reported = request.getAttribute(SPRING_PATH_VARIABLES_ATTRIBUTE);
    Map<String, Object> frameworkVariables = new LinkedHashMap<>();
    if (reported instanceof Map<?, ?> variables) {
      variables.forEach(
          (name, value) -> {
            if (name instanceof String text && value != null) {
              frameworkVariables.put(text, value);
            }
          });
    }
    return HttpBodyPolicyEngine.selectRequestPathParameters(
        "INCOMING", method, path, frameworkVariables, generation);
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

  private static String attributeName(String header) {
    return header.replace('-', '_');
  }

  private static String truncate(String value, int limit) {
    return value.substring(0, Math.min(value.length(), limit));
  }
}
