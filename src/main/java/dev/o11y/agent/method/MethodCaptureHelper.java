package dev.o11y.agent.method;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MethodCaptureHelper {
  private static final java.util.logging.Logger DIAGNOSTIC_LOGGER =
      java.util.logging.Logger.getLogger(MethodCaptureHelper.class.getName());
  private static final String POLICY_PROPERTY = "o11y.dynamic.method.compiled";
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final ConcurrentHashMap<String, InstrumentHandle> INSTRUMENTS =
      new ConcurrentHashMap<>();
  private static final Set<String> BLOCKED_PROPERTY_NAMES =
      Set.of(
          "class",
          "classLoader",
          "declaringClass",
          "enclosingClass",
          "module",
          "protectionDomain",
          "recordComponents",
          "signers");
  private static volatile String parsedSource = "";
  private static volatile List<MethodRule> parsedRules = List.of();
  private static volatile Meter meter;
  private static volatile Logger logger;

  private MethodCaptureHelper() {}

  public static void onExit(
      String className,
      String methodName,
      Object[] arguments,
      Object returned,
      Throwable error,
      long startNanos,
      long endNanos) {
    for (MethodRule rule : currentRules()) {
      if (rule.className.equals(className) && rule.methodName.equals(methodName)) {
        apply(rule, arguments, returned, error, startNanos, endNanos);
      }
    }
  }

  private static void apply(
      MethodRule rule,
      Object[] arguments,
      Object returned,
      Throwable error,
      long startNanos,
      long endNanos) {
    Span span = Span.current();
    Map<String, Object> logAttributes = new LinkedHashMap<>();
    AttributesBuilder metricAttributes = Attributes.builder();
    for (CaptureRule capture : rule.captures) {
      Object raw = resolve(capture.source, arguments, returned, startNanos, endNanos);
      Object typed = coerce(raw, capture.type);
      if (typed == null) {
        continue;
      }
      if (capture.span && span.isRecording()) {
        setSpanAttribute(span, capture.attribute, typed);
      }
      if (capture.log) {
        logAttributes.put(capture.attribute, typed);
      }
      if (capture.metric) {
        putAttribute(
            metricAttributes,
            capture.attribute,
            transform(String.valueOf(typed), capture.valuePolicy));
      }
    }

    if (error == null) {
      Attributes labels = metricAttributes.build();
      for (MetricRule metricRule : rule.metrics) {
        Double value =
            number(
                resolve(
                    metricRule.value,
                    arguments,
                    returned,
                    startNanos,
                    endNanos));
        if (value == null) {
          continue;
        }
        INSTRUMENTS
            .computeIfAbsent(metricRule.name, ignored -> createInstrument(metricRule))
            .record(value, labels);
      }
    }

    if (rule.logEnabled) {
      emitLog(rule, logAttributes);
    }
  }

  private static List<MethodRule> currentRules() {
    String source = currentProperty(POLICY_PROPERTY);
    if (source.equals(parsedSource)) {
      return parsedRules;
    }
    synchronized (MethodCaptureHelper.class) {
      if (!source.equals(parsedSource)) {
        try {
          parsedRules = parse(source);
          parsedSource = source;
        } catch (RuntimeException error) {
          // Fail closed and remember the rejected source. Retrying it on every
          // instrumented invocation would add latency and flood application logs.
          parsedRules = List.of();
          parsedSource = source;
          DIAGNOSTIC_LOGGER.log(
              java.util.logging.Level.WARNING,
              "o11y_method_policy=compiled_policy_rejected");
        }
      }
      return parsedRules;
    }
  }

  private static String currentProperty(String property) {
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    if (generation.isBlank()) {
      return System.getProperty(property, "");
    }
    return System.getProperty(property + ".generation." + generation, "");
  }

  static List<MethodRule> parse(String source) {
    if (source.isBlank()) {
      return List.of();
    }
    Map<String, MethodRule> rules = new LinkedHashMap<>();
    for (String line : source.split("\\n")) {
      if (line.isBlank() || line.startsWith("V|")) {
        continue;
      }
      String[] fields = line.split("\\|", -1);
      if ("M".equals(fields[0]) && fields.length == 7) {
        MethodRule rule = new MethodRule();
        rule.id = decoded(fields[1]);
        rule.className = decoded(fields[2]);
        rule.methodName = decoded(fields[3]);
        rule.logEnabled = Boolean.parseBoolean(fields[4]);
        rule.logSeverity = decoded(fields[5]);
        rule.logBody = decoded(fields[6]);
        rules.put(rule.id, rule);
      } else if ("C".equals(fields[0]) && fields.length == 13) {
        MethodRule owner = rules.get(decoded(fields[1]));
        if (owner == null) {
          throw new IllegalArgumentException("capture owner missing");
        }
        CaptureRule capture = new CaptureRule();
        capture.source =
            source(
                fields[2],
                Integer.parseInt(fields[3]),
                decoded(fields[4]),
                Double.parseDouble(fields[5]));
        capture.attribute = decoded(fields[6]);
        capture.type = fields[7];
        List<String> destinations = List.of(fields[8].split(","));
        capture.span = destinations.contains("SPAN");
        capture.metric = destinations.contains("METRIC");
        capture.log = destinations.contains("LOG");
        capture.valuePolicy =
            valuePolicy(
                fields[9],
                decoded(fields[10]),
                decoded(fields[11]),
                decoded(fields[12]));
        owner.captures.add(capture);
      } else if ("I".equals(fields[0]) && fields.length == 11) {
        MethodRule owner = rules.get(decoded(fields[1]));
        if (owner == null) {
          throw new IllegalArgumentException("instrument owner missing");
        }
        MetricRule metricRule = new MetricRule();
        metricRule.name = decoded(fields[2]);
        metricRule.instrument = fields[3];
        metricRule.unit = decoded(fields[4]);
        metricRule.description = decoded(fields[5]);
        metricRule.value =
            source(
                fields[6],
                Integer.parseInt(fields[7]),
                decoded(fields[8]),
                Double.parseDouble(fields[9]));
        if (!fields[10].isBlank()) {
          for (String bucket : fields[10].split(",")) {
            metricRule.buckets.add(Double.parseDouble(bucket));
          }
        }
        owner.metrics.add(metricRule);
      } else {
        throw new IllegalArgumentException("invalid compiled policy line");
      }
    }
    return List.copyOf(rules.values());
  }

  private static ValueSource source(
      String type, int argumentIndex, String path, double constant) {
    ValueSource source = new ValueSource();
    source.type = type;
    source.argumentIndex = argumentIndex;
    source.path = path;
    source.constant = constant;
    return source;
  }

  private static ValuePolicy valuePolicy(
      String type, String allowed, String fallback, String ranges) {
    ValuePolicy policy = new ValuePolicy();
    policy.type = type;
    policy.fallback = fallback;
    if (!allowed.isBlank()) {
      policy.allowed.addAll(List.of(allowed.split("\u001f")));
    }
    if (!ranges.isBlank()) {
      for (String encodedRange : ranges.split(",")) {
        String[] parts = encodedRange.split(":", 2);
        Range range = new Range();
        range.max = "*".equals(parts[0]) ? null : Double.parseDouble(parts[0]);
        range.label = decoded(parts[1]);
        policy.ranges.add(range);
      }
    }
    return policy;
  }

  static Object resolve(
      ValueSource source,
      Object[] arguments,
      Object returned,
      long startNanos,
      long endNanos) {
    Object root;
    switch (source.type) {
      case "ARGUMENT" ->
          root =
              source.argumentIndex >= 0 && source.argumentIndex < arguments.length
                  ? arguments[source.argumentIndex]
                  : null;
      case "RETURN" -> root = returned;
      case "DURATION" -> {
        return (endNanos - startNanos) / 1_000_000_000d;
      }
      case "CONSTANT" -> {
        return source.constant;
      }
      default -> {
        return null;
      }
    }
    String path = normalizeObjectPath(source.path);
    if (root == null || path.isBlank()) {
      return root;
    }
    Object value = root;
    for (String part : path.split("\\.")) {
      value = property(value, part);
      if (value == null) {
        break;
      }
    }
    return value;
  }

  private static String normalizeObjectPath(String path) {
    return path == null ? "" : path.trim();
  }

  private static Object property(Object target, String name) {
    if (target == null || name == null || name.isBlank()) {
      return null;
    }
    if (target instanceof Map<?, ?> map) {
      return map.get(name);
    }
    if (BLOCKED_PROPERTY_NAMES.contains(name)
        || target instanceof Class<?>
        || target instanceof ClassLoader
        || target instanceof Module) {
      return null;
    }

    Class<?> type = target.getClass();
    if (type.isRecord()) {
      for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
        if (component.getName().equals(name)) {
          return invokeAccessor(component.getAccessor(), target, false);
        }
      }
      return null;
    }

    String capitalized =
        Character.toUpperCase(name.charAt(0)) + name.substring(1);
    Object value = invokeGetter(type, target, "get" + capitalized, false);
    if (value != Unresolved.VALUE) {
      return value;
    }
    value = invokeGetter(type, target, "is" + capitalized, true);
    return value == Unresolved.VALUE ? null : value;
  }

  private static Object invokeGetter(
      Class<?> type, Object target, String methodName, boolean booleanOnly) {
    try {
      Method method = type.getMethod(methodName);
      if (method.getParameterCount() != 0
          || method.getReturnType() == Void.TYPE
          || booleanOnly
              && method.getReturnType() != Boolean.TYPE
              && method.getReturnType() != Boolean.class) {
        return Unresolved.VALUE;
      }
      return invokeAccessor(method, target, true);
    } catch (NoSuchMethodException | SecurityException ignored) {
      return Unresolved.VALUE;
    }
  }

  private static Object invokeAccessor(Method method, Object target, boolean unresolvedOnFailure) {
    try {
      if (!method.canAccess(target)) {
        return unresolvedOnFailure ? Unresolved.VALUE : null;
      }
      return method.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return unresolvedOnFailure ? Unresolved.VALUE : null;
    }
  }

  private enum Unresolved {
    VALUE
  }

  static Object coerce(Object value, String type) {
    if (value == null) {
      return null;
    }
    try {
      return switch (type) {
        case "DOUBLE" -> number(value);
        case "LONG" -> longValue(value);
        case "BOOLEAN" -> booleanValue(value);
        default -> scalarText(value);
      };
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return value instanceof String text ? Long.parseLong(text) : null;
  }

  private static Boolean booleanValue(Object value) {
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    if (value instanceof String text) {
      if ("true".equalsIgnoreCase(text)) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(text)) {
        return Boolean.FALSE;
      }
    }
    return null;
  }

  /**
   * Converts only scalar value types whose textual representation is part of their contract.
   * Calling an arbitrary application's {@code toString()} from instrumentation can execute user
   * code, traverse an object graph, expose secrets, or add unbounded latency to the observed
   * method.
   */
  private static String scalarText(Object value) {
    String text =
        switch (value) {
          case String string -> string;
          case Character character -> character.toString();
          case Boolean booleanValue -> booleanValue.toString();
          case Byte number -> number.toString();
          case Short number -> number.toString();
          case Integer number -> number.toString();
          case Long number -> number.toString();
          case Float number -> number.toString();
          case Double number -> number.toString();
          case BigDecimal number -> number.toPlainString();
          case BigInteger number -> number.toString();
          case Enum<?> enumeration -> enumeration.name();
          case UUID uuid -> uuid.toString();
          default -> null;
        };
    return text == null ? null : truncate(text);
  }

  private static Object transform(String raw, ValuePolicy policy) {
    if (raw == null || raw.isBlank()) {
      if ("BOOLEAN".equals(policy.type)) {
        return Boolean.parseBoolean(policy.fallback);
      }
      return policy.fallback;
    }
    if ("BOOLEAN".equals(policy.type)) {
      if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
        return Boolean.parseBoolean(raw);
      }
      return Boolean.parseBoolean(policy.fallback);
    }
    if ("RANGE".equals(policy.type)) {
      try {
        double number = Double.parseDouble(raw);
        for (Range range : policy.ranges) {
          if (range.max == null || number <= range.max) {
            return range.label;
          }
        }
      } catch (NumberFormatException ignored) {
        // The fallback below is used.
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

  private static InstrumentHandle createInstrument(MetricRule policy) {
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
      return histogram::record;
    }
    if ("UP_DOWN_COUNTER".equals(policy.instrument)) {
      DoubleUpDownCounter counter =
          meter()
              .upDownCounterBuilder(policy.name)
              .ofDoubles()
              .setUnit(policy.unit)
              .setDescription(policy.description)
              .build();
      return counter::add;
    }
    DoubleCounter counter =
        meter()
            .counterBuilder(policy.name)
            .ofDoubles()
            .setUnit(policy.unit)
            .setDescription(policy.description)
            .build();
    return (value, attributes) -> {
      if (value >= 0) {
        counter.add(value, attributes);
      }
    };
  }

  private static void emitLog(MethodRule rule, Map<String, Object> attributes) {
    LogRecordBuilder record =
        logger()
            .logRecordBuilder()
            .setContext(Context.current())
            .setSeverity(severity(rule.logSeverity))
            .setSeverityText(rule.logSeverity)
            .setBody(rule.logBody);
    for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
      putLog(record, attribute.getKey(), attribute.getValue());
    }
    record.emit();
  }

  private static Meter meter() {
    Meter current = meter;
    if (current == null) {
      synchronized (MethodCaptureHelper.class) {
        current = meter;
        if (current == null) {
          current =
              GlobalOpenTelemetry.get()
                  .meterBuilder("dev.o11y.dynamic-method-policy")
                  .setInstrumentationVersion("1.0.0")
                  .build();
          meter = current;
        }
      }
    }
    return current;
  }

  private static Logger logger() {
    Logger current = logger;
    if (current == null) {
      synchronized (MethodCaptureHelper.class) {
        current = logger;
        if (current == null) {
          current =
              GlobalOpenTelemetry.get()
                  .getLogsBridge()
                  .loggerBuilder("dev.o11y.dynamic-method-policy")
                  .setInstrumentationVersion("1.0.0")
                  .build();
          logger = current;
        }
      }
    }
    return current;
  }

  private static Double number(Object value) {
    double candidate;
    if (value instanceof BigDecimal decimal) {
      candidate = decimal.doubleValue();
    } else if (value instanceof Number number) {
      candidate = number.doubleValue();
    } else if (value instanceof String text) {
      try {
        candidate = Double.parseDouble(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    } else {
      return null;
    }
    return Double.isFinite(candidate) ? candidate : null;
  }

  private static void putAttribute(
      AttributesBuilder target, String name, Object value) {
    if (value instanceof Boolean booleanValue) {
      target.put(AttributeKey.booleanKey(name), booleanValue);
    } else if (value instanceof Number number) {
      target.put(AttributeKey.doubleKey(name), number.doubleValue());
    } else if (value != null) {
      target.put(AttributeKey.stringKey(name), truncate(String.valueOf(value)));
    }
  }

  private static void setSpanAttribute(Span span, String name, Object value) {
    if (value instanceof Boolean booleanValue) {
      span.setAttribute(name, booleanValue);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      span.setAttribute(name, ((Number) value).longValue());
    } else if (value instanceof Number number) {
      span.setAttribute(name, number.doubleValue());
    } else if (value != null) {
      span.setAttribute(name, truncate(String.valueOf(value)));
    }
  }

  private static void putLog(LogRecordBuilder record, String name, Object value) {
    if (value instanceof Boolean booleanValue) {
      record.setAttribute(AttributeKey.booleanKey(name), booleanValue);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      record.setAttribute(AttributeKey.longKey(name), ((Number) value).longValue());
    } else if (value instanceof Number number) {
      record.setAttribute(AttributeKey.doubleKey(name), number.doubleValue());
    } else if (value != null) {
      record.setAttribute(AttributeKey.stringKey(name), truncate(String.valueOf(value)));
    }
  }

  private static Severity severity(String value) {
    try {
      return Severity.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (Exception ignored) {
      return Severity.INFO;
    }
  }

  private static String decoded(String value) {
    if (value.isEmpty()) {
      return "";
    }
    return new String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8);
  }

  private static String truncate(String value) {
    return value.substring(0, Math.min(value.length(), 128));
  }

  static final class MethodRule {
    String id = "";
    String className = "";
    String methodName = "";
    boolean logEnabled;
    String logSeverity = "INFO";
    String logBody = "Dynamic method policy captured";
    final List<CaptureRule> captures = new ArrayList<>();
    final List<MetricRule> metrics = new ArrayList<>();
  }

  static final class CaptureRule {
    ValueSource source = new ValueSource();
    String attribute = "";
    String type = "STRING";
    boolean span;
    boolean metric;
    boolean log;
    ValuePolicy valuePolicy = new ValuePolicy();
  }

  static final class MetricRule {
    String name = "";
    String instrument = "COUNTER";
    String unit = "1";
    String description = "";
    ValueSource value = new ValueSource();
    final List<Double> buckets = new ArrayList<>();
  }

  static final class ValueSource {
    String type = "CONSTANT";
    int argumentIndex = -1;
    String path = "";
    double constant = 1;
  }

  static final class ValuePolicy {
    String type = "ENUM";
    final List<String> allowed = new ArrayList<>();
    String fallback = "OTHER";
    final List<Range> ranges = new ArrayList<>();
  }

  static final class Range {
    Double max;
    String label = "";
  }

  @FunctionalInterface
  interface InstrumentHandle {
    void record(double value, Attributes attributes);
  }
}
