package dev.o11y.agent.http.runtime;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Executes compiled HTTP body policies without depending on application libraries. */
public final class HttpBodyPolicyEngine {
  public static final String POLICY_PROPERTY = "o11y.dynamic.body.compiled";
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String LIST_SEPARATOR = "\u001f";
  private static final int MAX_CAPTURED_STRING = 256;
  private static final int MAX_SELECTED_NAMES = 16;
  private static final int MAX_VALUES_PER_NAME = 4;
  private static final int MAX_RAW_QUERY_CHARS = 8192;
  private static final Pattern PATH_TOKEN =
      Pattern.compile("(?:^|\\.)([A-Za-z_][A-Za-z0-9_-]*)|\\[([0-9]{1,4})]");
  private static final java.util.logging.Logger DIAGNOSTIC_LOGGER =
      java.util.logging.Logger.getLogger(HttpBodyPolicyEngine.class.getName());
  private static final int RETAINED_POLICY_SOURCES = 16;
  private static final PolicyCache PARSED_POLICIES = new PolicyCache();
  private static final ConcurrentHashMap<String, InstrumentHandle> INSTRUMENTS =
      new ConcurrentHashMap<>();

  private static volatile Meter meter;
  private static volatile Logger logger;

  private HttpBodyPolicyEngine() {}

  public static int captureLimit(
      String direction, String method, String path, String generation) {
    CapturePlan plan = capturePlan(direction, method, path, generation);
    return Math.max(plan.requestLimit(), plan.responseLimit());
  }

  /** Returns the independently bounded request and response bodies needed by matching rules. */
  public static CapturePlan capturePlan(
      String direction, String method, String path, String generation) {
    return capturePlan(direction, method, path, null, generation);
  }

  /** Refines a capture plan once the response status is known, before reading its body. */
  public static CapturePlan capturePlanAfterResponse(
      String direction, String method, String path, int responseStatus, String generation) {
    return capturePlan(direction, method, path, responseStatus, generation);
  }

  /** Returns whether at least one event rule can match this request before its response exists. */
  public static boolean hasCandidate(
      String direction, String method, String path, String generation) {
    for (EventRule rule : current(generation).events) {
      if (candidate(rule, direction, method, path)) {
        return true;
      }
    }
    return false;
  }

  public static List<String> requiredRequestHeaderNames(
      String direction, String method, String path, String generation) {
    return requiredNames(direction, method, path, generation, "REQUEST_HEADER");
  }

  public static List<String> requiredResponseHeaderNames(
      String direction, String method, String path, String generation) {
    return requiredNames(direction, method, path, generation, "RESPONSE_HEADER");
  }

  public static List<String> requiredRequestQueryNames(
      String direction, String method, String path, String generation) {
    return requiredNames(direction, method, path, generation, "REQUEST_QUERY");
  }

  public static List<String> requiredRequestPathParameterNames(
      String direction, String method, String path, String generation) {
    return requiredNames(direction, method, path, generation, "REQUEST_PATH_PARAM");
  }

  private static List<String> requiredNames(
      String direction, String method, String path, String generation, String source) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (EventRule rule : current(generation).events) {
      if (!candidate(rule, direction, method, path)) {
        continue;
      }
      for (Condition condition : rule.conditions) {
        if (source.equals(condition.source)) {
          names.add(condition.path);
        }
      }
      for (FieldRule field : rule.fields) {
        if (source.equals(field.source)) {
          names.add(field.path);
        }
      }
    }
    return names.stream().limit(MAX_SELECTED_NAMES).toList();
  }

  /** Selects only explicitly requested, case-sensitive query names from a bounded raw query. */
  public static Map<String, List<String>> selectQueryParameters(
      String rawQuery, List<String> selectedNames) {
    if (rawQuery == null
        || rawQuery.isBlank()
        || rawQuery.length() > MAX_RAW_QUERY_CHARS
        || selectedNames == null
        || selectedNames.isEmpty()) {
      return Map.of();
    }
    Set<String> selected =
        Set.copyOf(selectedNames.stream().limit(MAX_SELECTED_NAMES).toList());
    Map<String, List<String>> mutable = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      int separator = pair.indexOf('=');
      String name = separator < 0 ? pair : pair.substring(0, separator);
      if (!selected.contains(name)) {
        continue;
      }
      List<String> values = mutable.computeIfAbsent(name, ignored -> new ArrayList<>());
      if (values.size() >= MAX_VALUES_PER_NAME) {
        continue;
      }
      String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
      try {
        values.add(truncate(URLDecoder.decode(rawValue, StandardCharsets.UTF_8), MAX_CAPTURED_STRING));
      } catch (IllegalArgumentException ignored) {
        // An invalid percent-encoding is omitted instead of being interpreted ambiguously.
      }
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    mutable.forEach((name, values) -> result.put(name, List.copyOf(values)));
    return Map.copyOf(result);
  }

  /**
   * Selects named route variables reported by a framework, then fills missing names from the
   * matching policy path template. The fallback keeps Servlet support useful without coupling the
   * runtime to Spring classes.
   */
  public static Map<String, List<String>> selectRequestPathParameters(
      String direction,
      String method,
      String actualPath,
      Map<String, ?> frameworkVariables,
      String generation) {
    List<String> required =
        requiredRequestPathParameterNames(direction, method, actualPath, generation);
    if (required.isEmpty()) {
      return Map.of();
    }
    Set<String> selected = Set.copyOf(required);
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (frameworkVariables != null) {
      for (Map.Entry<String, ?> entry : frameworkVariables.entrySet()) {
        if (result.size() >= MAX_SELECTED_NAMES || !selected.contains(entry.getKey())) {
          continue;
        }
        Object value = entry.getValue();
        if (value != null) {
          result.put(
              entry.getKey(),
              List.of(truncate(String.valueOf(value), MAX_CAPTURED_STRING)));
        }
      }
    }
    if (result.size() == selected.size()) {
      return Map.copyOf(result);
    }
    for (EventRule rule : current(generation).events) {
      if (!candidate(rule, direction, method, actualPath)) {
        continue;
      }
      for (Condition condition : rule.conditions) {
        if (!"REQUEST_PATH".equals(condition.source)) {
          continue;
        }
        for (String template : condition.values) {
          Map<String, String> extracted = pathParameters(template, actualPath);
          extracted.forEach(
              (name, value) -> {
                if (selected.contains(name) && !result.containsKey(name)) {
                  result.put(name, List.of(truncate(value, MAX_CAPTURED_STRING)));
                }
              });
        }
      }
    }
    return Map.copyOf(result);
  }

  private static CapturePlan capturePlan(
      String direction,
      String method,
      String path,
      Integer responseStatus,
      String generation) {
    int requestLimit = 0;
    int responseLimit = 0;
    for (EventRule rule : current(generation).events) {
      if (candidate(rule, direction, method, path)
          && (responseStatus == null
              || nonBodyConditionsMatch(rule, method, path, responseStatus))) {
        if (usesBody(rule, "REQUEST_BODY")) {
          requestLimit = Math.max(requestLimit, rule.maxBodyBytes);
        }
        if (usesBody(rule, "RESPONSE_BODY")) {
          responseLimit = Math.max(responseLimit, rule.maxBodyBytes);
        }
      }
    }
    return new CapturePlan(requestLimit, responseLimit);
  }

  public static int process(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Context context,
      String generation) {
    return process(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        context,
        generation);
  }

  public static int process(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Context context,
      String generation) {
    return process(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        requestHeaders,
        responseHeaders,
        requestQuery,
        Map.of(),
        context,
        generation);
  }

  public static int process(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters,
      Context context,
      String generation) {
    return process(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        requestHeaders,
        responseHeaders,
        requestQuery,
        requestPathParameters,
        context,
        generation,
        null,
        null);
  }

  public static int processWithErrorType(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters,
      Context context,
      String generation,
      String errorType) {
    return process(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        requestHeaders,
        responseHeaders,
        requestQuery,
        requestPathParameters,
        context,
        generation,
        errorType,
        null);
  }

  /**
   * Processes an HTTP event while returning its span destinations to the caller.
   *
   * <p>This is used by server integrations that finish inside an {@link
   * io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor}. Adding the values to
   * that extractor's builder guarantees that the owning Instrumenter writes them before ending
   * the server span. Logs and metrics are still emitted by this runtime exactly once.
   */
  public static int processCollectingSpanAttributes(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters,
      Context context,
      String generation,
      Map<String, Object> spanAttributes) {
    return processCollectingSpanAttributes(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        requestHeaders,
        responseHeaders,
        requestQuery,
        requestPathParameters,
        context,
        generation,
        null,
        spanAttributes);
  }

  public static int processCollectingSpanAttributes(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters,
      Context context,
      String generation,
      String errorType,
      Map<String, Object> spanAttributes) {
    if (spanAttributes == null) {
      throw new IllegalArgumentException("spanAttributes is required");
    }
    return process(
        direction,
        method,
        path,
        requestContentType,
        requestContentEncoding,
        requestBody,
        responseStatus,
        responseContentType,
        responseContentEncoding,
        responseBody,
        requestHeaders,
        responseHeaders,
        requestQuery,
        requestPathParameters,
        context,
        generation,
        errorType,
        spanAttributes);
  }

  private static int process(
      String direction,
      String method,
      String path,
      String requestContentType,
      String requestContentEncoding,
      byte[] requestBody,
      int responseStatus,
      String responseContentType,
      String responseContentEncoding,
      byte[] responseBody,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters,
      Context context,
      String generation,
      String errorType,
      Map<String, Object> spanAttributes) {
    RuntimePolicy policy = current(generation);
    Map<String, List<String>> safeRequestHeaders = boundedMap(requestHeaders, true);
    Map<String, List<String>> safeResponseHeaders = boundedMap(responseHeaders, true);
    Map<String, List<String>> safeRequestQuery = boundedMap(requestQuery, false);
    Map<String, List<String>> safeRequestPathParameters =
        boundedMap(requestPathParameters, false);
    int emitted = 0;
    Map<BodyParseKey, Object> requestParses = new HashMap<>();
    Map<BodyParseKey, Object> responseParses = new HashMap<>();
    for (EventRule rule : policy.events) {
      if (!candidate(rule, direction, method, path)
          || !nonBodyConditionsMatch(rule, method, path, responseStatus)) {
        continue;
      }
      Object requestRoot =
          usesBody(rule, "REQUEST_BODY")
              ? parseBodyCached(
                  requestParses,
                  requestBody,
                  requestContentType,
                  rule.requestContentType,
                  requestContentEncoding,
                  rule.maxBodyBytes)
              : null;
      Object responseRoot =
          usesBody(rule, "RESPONSE_BODY")
              ? parseBodyCached(
                  responseParses,
                  responseBody,
                  responseContentType,
                  rule.responseContentType,
                  responseContentEncoding,
                  rule.maxBodyBytes)
              : null;
      if (!conditionsMatch(
          rule,
          method,
          path,
          responseStatus,
          requestRoot,
          responseRoot,
          safeRequestHeaders,
          safeResponseHeaders,
          safeRequestQuery,
          safeRequestPathParameters)) {
        continue;
      }
      Map<String, ExtractedField> fields =
          extract(
              rule,
              requestRoot,
              responseRoot,
              safeRequestHeaders,
              safeResponseHeaders,
              safeRequestQuery,
              safeRequestPathParameters);
      derive(rule, fields);
      enrichSpan(context, rule, fields, spanAttributes);
      if (rule.logEnabled) {
        emitLog(context, rule, fields);
      }
      emitMetrics(
          context,
          policy.metrics,
          rule,
          fields,
          method,
          path,
          responseStatus,
          errorType);
      emitted++;
    }
    return emitted;
  }

  private static Object parseBodyCached(
      Map<BodyParseKey, Object> cache,
      byte[] body,
      String actualContentType,
      String expectedContentType,
      String encoding,
      int maxBytes) {
    BodyParseKey key =
        new BodyParseKey(
            mediaType(actualContentType),
            mediaType(expectedContentType),
            encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT),
            maxBytes);
    Object cached = cache.get(key);
    if (cached != null) {
      return cached == InvalidBody.INSTANCE ? null : cached;
    }
    Object parsed =
        parseBody(body, actualContentType, expectedContentType, encoding, maxBytes);
    cache.put(key, parsed == null ? InvalidBody.INSTANCE : parsed);
    return parsed;
  }

  private static boolean usesBody(EventRule rule, String source) {
    for (Condition condition : rule.conditions) {
      if (source.equals(condition.source)) {
        return true;
      }
    }
    for (FieldRule field : rule.fields) {
      if (source.equals(field.source)) {
        return true;
      }
    }
    return false;
  }

  private static boolean nonBodyConditionsMatch(
      EventRule rule, String method, String path, int responseStatus) {
    for (Condition condition : rule.conditions) {
      String actual =
          switch (condition.source) {
            case "REQUEST_METHOD" -> method;
            case "REQUEST_PATH" -> path;
            case "RESPONSE_STATUS" ->
                responseStatus > 0 ? String.valueOf(responseStatus) : null;
            default -> null;
          };
      if (actual == null) {
        continue;
      }
      boolean matches =
          "REQUEST_PATH".equals(condition.source)
              ? requestPathMatches(rule, actual, condition.values)
              : matchesValue(actual, condition.values);
      if (!matches) {
        return false;
      }
    }
    return true;
  }

  private static RuntimePolicy current(String generation) {
    String source =
        generation == null || generation.isBlank()
            ? System.getProperty(POLICY_PROPERTY, "")
            : System.getProperty(POLICY_PROPERTY + ".generation." + generation, "");
    synchronized (PARSED_POLICIES) {
      RuntimePolicy cached = PARSED_POLICIES.get(source);
      if (cached != null) {
        return cached;
      }
      RuntimePolicy parsed;
      try {
        parsed = parsePolicy(source);
      } catch (RuntimeException error) {
        parsed = new RuntimePolicy();
        DIAGNOSTIC_LOGGER.log(Level.WARNING, "o11y_body_policy=compiled_policy_rejected");
      }
      PARSED_POLICIES.put(source, parsed);
      return parsed;
    }
  }

  static int cachedPolicyCountForTest() {
    synchronized (PARSED_POLICIES) {
      return PARSED_POLICIES.size();
    }
  }

  static void clearPolicyCacheForTest() {
    synchronized (PARSED_POLICIES) {
      PARSED_POLICIES.clear();
    }
  }

  static RuntimePolicy parsePolicy(String source) {
    if (source == null || source.isBlank()) {
      return new RuntimePolicy();
    }
    String[] lines = source.split("\\n");
    int firstRecord = 0;
    while (firstRecord < lines.length && lines[firstRecord].isBlank()) {
      firstRecord++;
    }
    if (firstRecord == lines.length || !"V|1".equals(lines[firstRecord])) {
      throw new IllegalArgumentException("unsupported body policy format");
    }
    RuntimePolicy result = new RuntimePolicy();
    Map<String, EventRule> events = new LinkedHashMap<>();
    for (int index = firstRecord + 1; index < lines.length; index++) {
      String line = lines[index];
      if (line.isBlank()) {
        continue;
      }
      if (line.startsWith("V|")) {
        throw new IllegalArgumentException("duplicate body policy format record");
      }
      String[] fields = line.split("\\|", -1);
      switch (fields[0]) {
        case "E" -> {
          require(fields, 10);
          EventRule event = new EventRule();
          event.id = decoded(fields[1]);
          event.direction = decoded(fields[2]);
          event.requestContentType = decoded(fields[3]);
          event.responseContentType = decoded(fields[4]);
          event.maxBodyBytes = Integer.parseInt(fields[5]);
          event.eventName = decoded(fields[6]);
          event.logEnabled = Boolean.parseBoolean(fields[7]);
          event.logSeverity = decoded(fields[8]);
          event.logBody = decoded(fields[9]);
          events.put(event.id, event);
        }
        case "C" -> {
          require(fields, 6);
          EventRule event = owner(events, fields[1]);
          event.conditions.add(
              new Condition(
                  fields[2], decoded(fields[3]), fields[4], decodedList(fields[5])));
        }
        case "S" -> {
          require(fields, 6);
          owner(events, fields[1])
              .staticAttributes
              .add(
                  new StaticAttribute(
                      decoded(fields[2]),
                      decoded(fields[3]),
                      fields[4],
                      list(fields[5])));
        }
        case "F" -> {
          require(fields, 11);
          owner(events, fields[1])
              .fields
              .add(
                  new FieldRule(
                      fields[2],
                      decoded(fields[3]),
                      decoded(fields[4]),
                      fields[5],
                      list(fields[6]),
                      valuePolicy(fields, 7)));
        }
        case "D" -> {
          require(fields, 10);
          if (!"DOUBLE".equals(fields[4])) {
            throw new IllegalArgumentException("derived body fields support only DOUBLE");
          }
          owner(events, fields[1])
              .derivedFields
              .add(
                  new DerivedRule(
                      decoded(fields[2]),
                      decoded(fields[3]),
                      list(fields[5]),
                      valuePolicy(fields, 6)));
        }
        case "I" -> {
          require(fields, 10);
          EventMetric metric = new EventMetric();
          metric.eventName = decoded(fields[1]);
          metric.name = decoded(fields[2]);
          metric.instrument = fields[3];
          metric.unit = decoded(fields[4]);
          metric.description = decoded(fields[5]);
          metric.valueField = decoded(fields[6]);
          metric.dimensions = decodedList(fields[7]);
          metric.standardAttributes = decodedList(fields[8]);
          metric.buckets = doubles(fields[9]);
          result.metrics.add(metric);
        }
        default -> throw new IllegalArgumentException("invalid body policy record");
      }
    }
    for (EventRule event : events.values()) {
      Set<String> numericFields = new HashSet<>();
      for (FieldRule field : event.fields) {
        if ("DOUBLE".equals(field.type) || "LONG".equals(field.type)) {
          numericFields.add(field.attribute);
        }
      }
      for (DerivedRule field : event.derivedFields) {
        field.compiled = BodyNumericExpression.compile(field.expression, numericFields);
        numericFields.add(field.attribute);
      }
    }
    result.events.addAll(events.values());
    return result;
  }

  private static Map<String, ExtractedField> extract(
      EventRule rule,
      Object requestRoot,
      Object responseRoot,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters) {
    Map<String, ExtractedField> result = new LinkedHashMap<>();
    for (FieldRule field : rule.fields) {
      Object value =
          coerce(
              sourceValue(
                  field.source,
                  field.path,
                  requestRoot,
                  responseRoot,
                  requestHeaders,
                  responseHeaders,
                  requestQuery,
                  requestPathParameters),
              field.type);
      if (value != null) {
        result.put(
            field.attribute,
            new ExtractedField(field.attribute, field.destinations, field.valuePolicy, value));
      }
    }
    return result;
  }

  private static void derive(EventRule rule, Map<String, ExtractedField> fields) {
    Map<String, Double> numbers = new LinkedHashMap<>();
    fields.forEach(
        (name, field) -> {
          Double number = number(field.value);
          if (number != null) {
            numbers.put(name, number);
          }
        });
    for (DerivedRule field : rule.derivedFields) {
      if (field.compiled == null) {
        continue;
      }
      var value = field.compiled.evaluate(numbers);
      if (value.isPresent()) {
        double result = value.getAsDouble();
        numbers.put(field.attribute, result);
        fields.put(
            field.attribute,
            new ExtractedField(field.attribute, field.destinations, field.valuePolicy, result));
      }
    }
  }

  private static boolean candidate(
      EventRule rule, String direction, String method, String path) {
    if (!rule.direction.equalsIgnoreCase(direction)) {
      return false;
    }
    for (Condition condition : rule.conditions) {
      if ("REQUEST_METHOD".equals(condition.source)
          && !matchesValue(method, condition.values)) {
        return false;
      }
      if ("REQUEST_PATH".equals(condition.source)
          && !requestPathMatches(rule, path, condition.values)) {
        return false;
      }
    }
    return true;
  }

  private static boolean conditionsMatch(
      EventRule rule,
      String method,
      String path,
      int responseStatus,
      Object requestRoot,
      Object responseRoot,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters) {
    for (Condition condition : rule.conditions) {
      Object actual =
          switch (condition.source) {
            case "REQUEST_METHOD" -> method;
            case "REQUEST_PATH" -> path;
            case "RESPONSE_STATUS" ->
                responseStatus > 0 ? String.valueOf(responseStatus) : null;
            case "REQUEST_BODY" -> read(requestRoot, normalizePath(condition.path));
            case "RESPONSE_BODY" -> read(responseRoot, normalizePath(condition.path));
            case "REQUEST_HEADER" -> requestHeaders.get(condition.path);
            case "RESPONSE_HEADER" -> responseHeaders.get(condition.path);
            case "REQUEST_QUERY" -> requestQuery.get(condition.path);
            case "REQUEST_PATH_PARAM" -> requestPathParameters.get(condition.path);
            default -> null;
          };
      if (!conditionMatches(rule, condition, actual)) {
        return false;
      }
    }
    return true;
  }

  private static boolean conditionMatches(
      EventRule rule, Condition condition, Object actual) {
    if (actual == null || actual instanceof Map<?, ?>) {
      return false;
    }
    if (actual instanceof List<?> values) {
      Object first = values.isEmpty() ? null : values.getFirst();
      return first != null && matchesValue(String.valueOf(first), condition.values);
    }
    if ("REQUEST_PATH".equals(condition.source)) {
      return requestPathMatches(rule, String.valueOf(actual), condition.values);
    }
    return matchesValue(String.valueOf(actual), condition.values);
  }

  private static Object sourceValue(
      String source,
      String path,
      Object requestRoot,
      Object responseRoot,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      Map<String, List<String>> requestQuery,
      Map<String, List<String>> requestPathParameters) {
    return switch (source) {
      case "REQUEST_BODY" -> read(requestRoot, normalizePath(path));
      case "RESPONSE_BODY" -> read(responseRoot, normalizePath(path));
      case "REQUEST_HEADER" -> selectedValue(requestHeaders.get(path));
      case "RESPONSE_HEADER" -> selectedValue(responseHeaders.get(path));
      case "REQUEST_QUERY" -> selectedValue(requestQuery.get(path));
      case "REQUEST_PATH_PARAM" -> selectedValue(requestPathParameters.get(path));
      default -> null;
    };
  }

  private static Object selectedValue(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    return values.getFirst();
  }

  private static Map<String, List<String>> boundedMap(
      Map<String, List<String>> source, boolean normalizeNames) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : source.entrySet()) {
      if (result.size() >= MAX_SELECTED_NAMES) {
        break;
      }
      if (entry.getKey() == null) {
        continue;
      }
      String name = normalizeNames ? entry.getKey().toLowerCase(Locale.ROOT) : entry.getKey();
      if (name.isEmpty() || name.length() > 128 || entry.getValue() == null) {
        continue;
      }
      ArrayList<String> values = new ArrayList<>();
      for (String value : entry.getValue()) {
        if (values.size() >= MAX_VALUES_PER_NAME) {
          break;
        }
        if (value != null) {
          values.add(truncate(value, MAX_CAPTURED_STRING));
        }
      }
      if (!values.isEmpty()) {
        result.put(name, List.copyOf(values));
      }
    }
    return Map.copyOf(result);
  }

  private static Object read(Object root, String path) {
    Object current = root;
    Matcher matcher = PATH_TOKEN.matcher(path);
    int consumed = 0;
    while (matcher.find()) {
      if (matcher.start() != consumed) {
        return null;
      }
      if (matcher.group(1) != null) {
        current = current instanceof Map<?, ?> map ? map.get(matcher.group(1)) : null;
      } else {
        int index = Integer.parseInt(matcher.group(2));
        current = current instanceof List<?> list && index < list.size() ? list.get(index) : null;
      }
      if (current == null) {
        return null;
      }
      consumed = matcher.end();
    }
    return consumed == path.length() ? current : null;
  }

  private static Object parseBody(
      byte[] body,
      String actualContentType,
      String expectedContentType,
      String encoding,
      int maxBytes) {
    if (body == null || body.length == 0 || !jsonContentType(actualContentType, expectedContentType)) {
      return null;
    }
    byte[] decoded = decodeBody(body, encoding, maxBytes);
    if (decoded == null) {
      return null;
    }
    try {
      Object root = BoundedJsonParser.parse(new String(decoded, StandardCharsets.UTF_8));
      return root instanceof Map<?, ?> ? root : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static byte[] decodeBody(byte[] body, String encoding, int maxBytes) {
    boolean gzip = "gzip".equalsIgnoreCase(encoding == null ? "" : encoding.trim());
    boolean gzipBytes = body.length >= 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b;
    if (body.length > maxBytes && !(gzip && gzipBytes)) {
      return null;
    }
    if (encoding != null
        && !encoding.isBlank()
        && !"identity".equalsIgnoreCase(encoding)
        && !gzip) {
      return null;
    }
    try {
      InputStream input = new ByteArrayInputStream(body);
      if (gzip && gzipBytes) {
        input = new GZIPInputStream(input);
      }
      return readBounded(input, maxBytes);
    } catch (IOException ignored) {
      return null;
    }
  }

  private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    byte[] chunk = new byte[4096];
    int total = 0;
    int read;
    while ((read = input.read(chunk)) >= 0) {
      total += read;
      if (total > maxBytes) {
        return null;
      }
      output.write(chunk, 0, read);
    }
    return output.toByteArray();
  }

  private static void enrichSpan(
      Context context,
      EventRule rule,
      Map<String, ExtractedField> fields,
      Map<String, Object> spanAttributes) {
    if (spanAttributes != null) {
      for (StaticAttribute attribute : rule.staticAttributes) {
        if (attribute.destinations.contains("SPAN")) {
          Object value = typedStatic(attribute);
          if (value != null) {
            spanAttributes.put(attribute.attribute, value);
          }
        }
      }
      for (ExtractedField field : fields.values()) {
        if (field.destinations.contains("SPAN") && field.value != null) {
          spanAttributes.put(field.attribute, field.value);
        }
      }
      return;
    }
    io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.fromContext(context);
    if (span == null || !span.isRecording()) {
      return;
    }
    for (StaticAttribute attribute : rule.staticAttributes) {
      if (attribute.destinations.contains("SPAN")) {
        setSpanAttribute(span, attribute.attribute, typedStatic(attribute));
      }
    }
    for (ExtractedField field : fields.values()) {
      if (field.destinations.contains("SPAN")) {
        setSpanAttribute(span, field.attribute, field.value);
      }
    }
  }

  private static void emitLog(
      Context context, EventRule rule, Map<String, ExtractedField> fields) {
    LogRecordBuilder record =
        logger()
            .logRecordBuilder()
            .setContext(context)
            .setSeverity(severity(rule.logSeverity))
            .setSeverityText(rule.logSeverity)
            .setBody(rule.logBody);
    for (StaticAttribute attribute : rule.staticAttributes) {
      if (attribute.destinations.contains("LOG")) {
        putLog(record, attribute.attribute, typedStatic(attribute));
      }
    }
    for (ExtractedField field : fields.values()) {
      if (field.destinations.contains("LOG")) {
        putLog(record, field.attribute, field.value);
      }
    }
    record.emit();
  }

  private static void emitMetrics(
      Context context,
      List<EventMetric> metrics,
      EventRule rule,
      Map<String, ExtractedField> fields,
      String method,
      String path,
      int responseStatus,
      String errorType) {
    for (EventMetric metric : metrics) {
      if (!metric.eventName.equals(rule.eventName)) {
        continue;
      }
      Double value = 1d;
      if (!metric.valueField.isBlank()) {
        ExtractedField extracted = fields.get(metric.valueField);
        value = extracted == null ? null : number(extracted.value);
      }
      if (value == null || "COUNTER".equals(metric.instrument) && value < 0) {
        continue;
      }
      AttributesBuilder dimensions = Attributes.builder();
      boolean complete = true;
      for (String name : metric.dimensions) {
        ExtractedField field = fields.get(name);
        if (field == null || !field.destinations.contains("METRIC")) {
          complete = false;
          break;
        }
        putAttribute(dimensions, name, transform(String.valueOf(field.value), field.valuePolicy));
      }
      for (String name : metric.standardAttributes) {
        putStandardHttpAttribute(
            dimensions,
            name,
            standardHttpAttribute(
                name, rule, method, path, responseStatus, errorType));
      }
      if (!complete) {
        continue;
      }
      String identity = metric.instrument + '|' + metric.unit + '|' + metric.buckets;
      InstrumentHandle handle =
          INSTRUMENTS.computeIfAbsent(metric.name, ignored -> createInstrument(metric, identity));
      if (handle.identity.equals(identity)) {
        handle.recorder.record(value, dimensions.build(), context);
      }
    }
  }

  private static Object standardHttpAttribute(
      String name,
      EventRule rule,
      String method,
      String path,
      int responseStatus,
      String errorType) {
    return switch (name) {
      case "http.request.method" -> method;
      case "http.response.status_code" ->
          responseStatus > 0 ? (long) responseStatus : null;
      case "http.route" -> "INCOMING".equals(rule.direction) ? matchedRoute(rule, path) : null;
      case "error.type" ->
          errorType == null || errorType.isBlank()
              ? HttpErrorType.resolve(rule.direction, responseStatus, null)
              : errorType;
      default -> null;
    };
  }

  private static void putStandardHttpAttribute(
      AttributesBuilder target, String name, Object value) {
    if ("http.response.status_code".equals(name) && value instanceof Number number) {
      target.put(AttributeKey.longKey(name), number.longValue());
      return;
    }
    putAttribute(target, name, value);
  }

  private static String matchedRoute(EventRule rule, String actualPath) {
    for (Condition condition : rule.conditions) {
      if (!"REQUEST_PATH".equals(condition.source)) {
        continue;
      }
      for (String candidate : condition.values) {
        if (candidate.equals(actualPath) || !pathParameters(candidate, actualPath).isEmpty()) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static InstrumentHandle createInstrument(EventMetric policy, String identity) {
    if ("HISTOGRAM".equals(policy.instrument)) {
      var builder =
          meter()
              .histogramBuilder(policy.name)
              .setUnit(policy.unit)
              .setDescription(policy.description);
      if (!policy.buckets.isEmpty()) {
        builder.setExplicitBucketBoundariesAdvice(policy.buckets);
      }
      DoubleHistogram histogram = builder.build();
      return new InstrumentHandle(
          identity,
          (value, attributes, context) -> histogram.record(value, attributes, context));
    }
    DoubleCounter counter =
        meter()
            .counterBuilder(policy.name)
            .ofDoubles()
            .setUnit(policy.unit)
            .setDescription(policy.description)
            .build();
    return new InstrumentHandle(
        identity, (value, attributes, context) -> counter.add(value, attributes, context));
  }

  private static Object coerce(Object value, String type) {
    if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
      return null;
    }
    try {
      return switch (type) {
        case "DOUBLE" -> number(value);
        case "LONG" -> strictLong(value);
        case "BOOLEAN" -> {
          if (value instanceof Boolean bool) {
            yield bool;
          }
          String text = String.valueOf(value);
          if ("true".equalsIgnoreCase(text)) {
            yield Boolean.TRUE;
          }
          if ("false".equalsIgnoreCase(text)) {
            yield Boolean.FALSE;
          }
          yield null;
        }
        default -> truncate(String.valueOf(value), MAX_CAPTURED_STRING);
      };
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Object typedStatic(StaticAttribute attribute) {
    try {
      return switch (attribute.type) {
        case "DOUBLE" -> Double.parseDouble(attribute.value);
        case "LONG" -> Long.parseLong(attribute.value);
        case "BOOLEAN" -> Boolean.parseBoolean(attribute.value);
        default -> truncate(attribute.value, MAX_CAPTURED_STRING);
      };
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Object transform(String raw, ValuePolicy policy) {
    if ("PASSTHROUGH".equals(policy.type)) {
      return raw == null || raw.isBlank() ? null : raw;
    }
    if (raw == null || raw.isBlank()) {
      return "BOOLEAN".equals(policy.type) ? Boolean.parseBoolean(policy.fallback) : policy.fallback;
    }
    if ("BOOLEAN".equals(policy.type)) {
      return "true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)
          ? Boolean.parseBoolean(raw)
          : Boolean.parseBoolean(policy.fallback);
    }
    if ("RANGE".equals(policy.type)) {
      try {
        double value = Double.parseDouble(raw);
        for (ValueRange range : policy.ranges) {
          if (range.max == null || value <= range.max) {
            return range.label;
          }
        }
      } catch (NumberFormatException ignored) {
        // Use the configured fallback.
      }
      return policy.fallback;
    }
    for (String allowed : policy.allowed) {
      if (allowed.equalsIgnoreCase(raw)) {
        return allowed;
      }
    }
    return policy.fallback;
  }

  private static Double number(Object value) {
    try {
      double result =
          value instanceof Number number
              ? number.doubleValue()
              : Double.parseDouble(String.valueOf(value));
      return Double.isFinite(result) ? result : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Long strictLong(Object value) {
    try {
      if (value instanceof Byte
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long) {
        return ((Number) value).longValue();
      }
      if (value instanceof Number number) {
        return new BigDecimal(number.toString()).longValueExact();
      }
      return Long.parseLong(String.valueOf(value));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static boolean matchesValue(String actual, List<String> expected) {
    for (String value : expected) {
      if (value.equalsIgnoreCase(actual)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesPathValue(String actual, List<String> expected) {
    for (String template : expected) {
      if (template.equals(actual) || !pathParameters(template, actual).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static boolean requestPathMatches(
      EventRule rule, String actual, List<String> expected) {
    if (rule.direction.startsWith("KAFKA_") || rule.direction.startsWith("JMS_")) {
      return expected.stream().anyMatch(actual::equals);
    }
    return matchesPathValue(actual, expected);
  }

  private static Map<String, String> pathParameters(String template, String actual) {
    if (template == null || actual == null) {
      return Map.of();
    }
    String[] templateSegments = template.split("/", -1);
    String[] actualSegments = actual.split("/", -1);
    if (templateSegments.length != actualSegments.length) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    boolean hasParameter = false;
    for (int index = 0; index < templateSegments.length; index++) {
      String expected = templateSegments[index];
      String observed = actualSegments[index];
      if (expected.length() >= 3
          && expected.charAt(0) == '{'
          && expected.charAt(expected.length() - 1) == '}') {
        String name = expected.substring(1, expected.length() - 1);
        if (observed.isEmpty() || name.isEmpty() || result.putIfAbsent(name, observed) != null) {
          return Map.of();
        }
        hasParameter = true;
      } else if (!expected.equals(observed)) {
        return Map.of();
      }
    }
    return hasParameter ? Map.copyOf(result) : Map.of();
  }

  private static boolean jsonContentType(String actualValue, String expectedValue) {
    String actual = mediaType(actualValue);
    String expected = mediaType(expectedValue);
    return expected.equals(actual)
        || "application/*+json".equals(expected)
            && actual.startsWith("application/")
            && actual.endsWith("+json")
        || "application/json".equals(expected)
            && actual.startsWith("application/")
            && actual.endsWith("+json");
  }

  private static String mediaType(String value) {
    if (value == null) {
      return "";
    }
    int separator = value.indexOf(';');
    return (separator < 0 ? value : value.substring(0, separator))
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private static String normalizePath(String value) {
    String result = value == null ? "" : value.trim();
    if (result.startsWith("$")) {
      result = result.substring(1);
    }
    return result.startsWith(".") ? result.substring(1) : result;
  }

  private static Meter meter() {
    Meter result = meter;
    if (result == null) {
      synchronized (HttpBodyPolicyEngine.class) {
        result = meter;
        if (result == null) {
          result = GlobalOpenTelemetry.get().meterBuilder("dev.o11y.http-telemetry-events").build();
          meter = result;
        }
      }
    }
    return result;
  }

  private static Logger logger() {
    Logger result = logger;
    if (result == null) {
      synchronized (HttpBodyPolicyEngine.class) {
        result = logger;
        if (result == null) {
          result =
              GlobalOpenTelemetry.get()
                  .getLogsBridge()
                  .loggerBuilder("dev.o11y.http-telemetry-events")
                  .build();
          logger = result;
        }
      }
    }
    return result;
  }

  private static void putAttribute(AttributesBuilder target, String name, Object value) {
    if (value instanceof Boolean bool) {
      target.put(AttributeKey.booleanKey(name), bool);
    } else if (value instanceof Number number) {
      target.put(AttributeKey.doubleKey(name), number.doubleValue());
    } else if (value != null) {
      target.put(AttributeKey.stringKey(name), truncate(String.valueOf(value), 128));
    }
  }

  private static void setSpanAttribute(
      io.opentelemetry.api.trace.Span span, String name, Object value) {
    if (value instanceof Boolean bool) {
      span.setAttribute(name, bool);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      span.setAttribute(name, ((Number) value).longValue());
    } else if (value instanceof Number number) {
      span.setAttribute(name, number.doubleValue());
    } else if (value != null) {
      span.setAttribute(name, truncate(String.valueOf(value), MAX_CAPTURED_STRING));
    }
  }

  private static void putLog(LogRecordBuilder record, String name, Object value) {
    if (value instanceof Boolean bool) {
      record.setAttribute(AttributeKey.booleanKey(name), bool);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      record.setAttribute(AttributeKey.longKey(name), ((Number) value).longValue());
    } else if (value instanceof Number number) {
      record.setAttribute(AttributeKey.doubleKey(name), number.doubleValue());
    } else if (value != null) {
      record.setAttribute(
          AttributeKey.stringKey(name), truncate(String.valueOf(value), MAX_CAPTURED_STRING));
    }
  }

  private static Severity severity(String value) {
    try {
      return Severity.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (RuntimeException ignored) {
      return Severity.INFO;
    }
  }

  private static EventRule owner(Map<String, EventRule> events, String encodedOwner) {
    EventRule result = events.get(decoded(encodedOwner));
    if (result == null) {
      throw new IllegalArgumentException("body policy owner missing");
    }
    return result;
  }

  private static ValuePolicy valuePolicy(String[] fields, int offset) {
    ValuePolicy result = new ValuePolicy();
    result.type = fields[offset];
    result.allowed = decodedList(fields[offset + 1]);
    result.fallback = decoded(fields[offset + 2]);
    for (String range : list(fields[offset + 3])) {
      String[] parts = range.split(":", 2);
      if (parts.length == 2) {
        result.ranges.add(
            new ValueRange("*".equals(parts[0]) ? null : Double.parseDouble(parts[0]), decoded(parts[1])));
      }
    }
    return result;
  }

  private static List<Double> doubles(String value) {
    ArrayList<Double> result = new ArrayList<>();
    for (String item : list(value)) {
      result.add(Double.parseDouble(item));
    }
    return result;
  }

  private static List<String> decodedList(String value) {
    String decoded = decoded(value);
    return decoded.isEmpty() ? List.of() : List.of(decoded.split(LIST_SEPARATOR, -1));
  }

  private static List<String> list(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(value.split(",", -1));
  }

  private static String decoded(String value) {
    return value == null || value.isEmpty()
        ? ""
        : new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static void require(String[] fields, int length) {
    if (fields.length != length) {
      throw new IllegalArgumentException("invalid body policy record length");
    }
  }

  private static String truncate(String value, int length) {
    return value.substring(0, Math.min(length, value.length()));
  }

  /** Named explicitly because Java Agent helper injection cannot discover anonymous classes. */
  private static final class PolicyCache extends LinkedHashMap<String, RuntimePolicy> {
    private static final long serialVersionUID = 1L;

    private PolicyCache() {
      super(RETAINED_POLICY_SOURCES, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, RuntimePolicy> eldest) {
      return size() > RETAINED_POLICY_SOURCES;
    }
  }

  /** Immutable capture budget safe to inject into arbitrary application class loaders. */
  public record CapturePlan(int requestLimit, int responseLimit) {}

  private record BodyParseKey(
      String actualContentType, String expectedContentType, String encoding, int maxBytes) {}

  private enum InvalidBody {
    INSTANCE
  }

  static final class RuntimePolicy {
    final List<EventRule> events = new ArrayList<>();
    final List<EventMetric> metrics = new ArrayList<>();
  }

  static final class EventRule {
    String id = "";
    String direction = "INCOMING";
    String requestContentType = "application/json";
    String responseContentType = "application/json";
    int maxBodyBytes;
    String eventName = "";
    boolean logEnabled;
    String logSeverity = "INFO";
    String logBody = "";
    final List<Condition> conditions = new ArrayList<>();
    final List<StaticAttribute> staticAttributes = new ArrayList<>();
    final List<FieldRule> fields = new ArrayList<>();
    final List<DerivedRule> derivedFields = new ArrayList<>();
  }

  private record Condition(String source, String path, String operator, List<String> values) {}

  private record StaticAttribute(
      String attribute, String value, String type, List<String> destinations) {}

  private record FieldRule(
      String source,
      String path,
      String attribute,
      String type,
      List<String> destinations,
      ValuePolicy valuePolicy) {}

  private static final class DerivedRule {
    final String attribute;
    final String expression;
    final List<String> destinations;
    final ValuePolicy valuePolicy;
    BodyNumericExpression.Compiled compiled;

    private DerivedRule(
        String attribute,
        String expression,
        List<String> destinations,
        ValuePolicy valuePolicy) {
      this.attribute = attribute;
      this.expression = expression;
      this.destinations = destinations;
      this.valuePolicy = valuePolicy;
    }
  }

  private static final class ValuePolicy {
    String type = "ENUM";
    List<String> allowed = List.of();
    String fallback = "OTHER";
    final List<ValueRange> ranges = new ArrayList<>();
  }

  private record ValueRange(Double max, String label) {}

  private static final class EventMetric {
    String eventName = "";
    String name = "";
    String instrument = "COUNTER";
    String unit = "1";
    String description = "";
    String valueField = "";
    List<String> dimensions = List.of();
    List<String> standardAttributes = List.of();
    List<Double> buckets = List.of();
  }

  private record ExtractedField(
      String attribute, List<String> destinations, ValuePolicy valuePolicy, Object value) {}

  private record InstrumentHandle(String identity, Recorder recorder) {}

  @FunctionalInterface
  private interface Recorder {
    void record(double value, Attributes attributes, Context context);
  }
}
