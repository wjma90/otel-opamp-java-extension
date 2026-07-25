package dev.o11y.agent.http;

import dev.o11y.agent.policy.DynamicPolicy;
import dev.o11y.agent.policy.PolicyState;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizer;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizerProvider;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.ContextCustomizer;
import io.opentelemetry.instrumentation.api.instrumenter.OperationListener;
import io.opentelemetry.instrumentation.api.instrumenter.OperationMetrics;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DynamicHttpCustomizer implements InstrumenterCustomizerProvider {
  private static final ContextKey<Map<String, String>> HEADERS =
      ContextKey.named("o11y-http-policy-headers");
  private static final ContextKey<DynamicPolicy> POLICY =
      ContextKey.named("o11y-http-policy-snapshot");
  private static final ContextKey<Long> START_NANOS =
      ContextKey.named("o11y-http-policy-start");

  @Override
  public void customize(InstrumenterCustomizer customizer) {
    String direction;
    if (customizer.hasType(InstrumenterCustomizer.InstrumentationType.HTTP_SERVER)) {
      direction = "INCOMING";
    } else if (customizer.hasType(InstrumenterCustomizer.InstrumentationType.HTTP_CLIENT)) {
      direction = "OUTGOING";
    } else {
      return;
    }
    customizer.addContextCustomizer(new HeaderContextCustomizer(direction));
    if ("INCOMING".equals(direction)) {
      customizer.addAttributesExtractor(new HttpServerCompletionExtractor());
    }
    customizer.addOperationMetrics(new DynamicHttpMetrics(direction));
  }

  static final class HttpServerCompletionExtractor implements AttributesExtractor<Object, Object> {
    @Override
    public void onStart(AttributesBuilder attributes, Context context, Object request) {
      // The mutable callback holder is installed by the context customizer below.
    }

    @Override
    public void onEnd(
        AttributesBuilder attributes,
        Context context,
        Object request,
        Object response,
        Throwable error) {
      HttpServerCompletionBridge.complete(context, error)
          .forEach((name, value) -> putCompletionAttribute(attributes, name, value));
    }

    private static void putCompletionAttribute(
        AttributesBuilder target, String name, Object value) {
      if (value instanceof Boolean booleanValue) {
        target.put(AttributeKey.booleanKey(name), booleanValue);
      } else if (value instanceof Byte
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long) {
        target.put(AttributeKey.longKey(name), ((Number) value).longValue());
      } else if (value instanceof Number number) {
        target.put(AttributeKey.doubleKey(name), number.doubleValue());
      } else if (value instanceof List<?> values
          && values.stream().allMatch(String.class::isInstance)) {
        target.put(
            AttributeKey.stringArrayKey(name), values.stream().map(String.class::cast).toList());
      } else if (value != null) {
        target.put(AttributeKey.stringKey(name), truncate(String.valueOf(value), 256));
      }
    }
  }

  static final class HeaderContextCustomizer implements ContextCustomizer<Object> {
    private final String direction;

    private HeaderContextCustomizer(String direction) {
      this.direction = direction;
    }

    @Override
    public Context onStart(Context context, Object request, Attributes startAttributes) {
      DynamicPolicy current = PolicyState.current();
      Map<String, String> headers = captureHeaders(request, current, direction);
      Context result = context.with(POLICY, current);
      if ("INCOMING".equals(direction)) {
        result = HttpServerCompletionBridge.install(result);
      }
      return headers.isEmpty() ? result : result.with(HEADERS, Map.copyOf(headers));
    }

    static Map<String, String> captureHeaders(Object request, DynamicPolicy current) {
      return captureHeaders(request, current, "INCOMING");
    }

    static Map<String, String> captureHeaders(
        Object request, DynamicPolicy current, String direction) {
      Map<String, String> headers = new HashMap<>();
      HashSet<String> attempted = new HashSet<>();
      for (DynamicPolicy.HttpMetricPolicy metric : current.metricPolicies()) {
        if (!metric.enabled || !direction.equals(metric.direction)) {
          continue;
        }
        for (DynamicPolicy.AttributeSource attribute : metric.customAttributes) {
          String header = DynamicPolicy.normalizedHeaderName(attribute.header);
          if (attempted.add(header)) {
            String value = readHeader(request, header);
            if (value != null) {
              headers.put(header, truncate(value, 256));
            }
          }
        }
      }
      return headers;
    }

    private static String readHeader(Object request, String name) {
      if (request == null) {
        return null;
      }
      for (String methodName : List.of("getHeader", "header")) {
        Object value = invokeString(request, methodName, name);
        if (value != null) {
          return String.valueOf(value);
        }
      }
      Object firstHeader = invokeString(request, "getFirstHeader", name);
      if (firstHeader != null) {
        Object value = invokeNoArgs(firstHeader, "getValue");
        return value == null ? String.valueOf(firstHeader) : String.valueOf(value);
      }
      Object headers = invokeNoArgs(request, "getHeaders");
      Object value = invokeString(headers, "getFirst", name);
      return value == null ? null : String.valueOf(value);
    }

    private static Object invokeString(Object target, String name, String argument) {
      if (target == null) {
        return null;
      }
      try {
        Method method = target.getClass().getMethod(name, String.class);
        if (!method.canAccess(target)) {
          method.setAccessible(true);
        }
        return method.invoke(target, argument);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        return null;
      }
    }

    private static Object invokeNoArgs(Object target, String name) {
      if (target == null) {
        return null;
      }
      try {
        Method method = target.getClass().getMethod(name);
        if (!method.canAccess(target)) {
          method.setAccessible(true);
        }
        return method.invoke(target);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        return null;
      }
    }
  }

  static final class DynamicHttpMetrics implements OperationMetrics {
    private final String direction;

    private DynamicHttpMetrics(String direction) {
      this.direction = direction;
    }

    @Override
    public OperationListener create(Meter meter) {
      ConcurrentHashMap<String, InstrumentHandle> instruments = new ConcurrentHashMap<>();

      return new OperationListener() {
        @Override
        public Context onStart(Context context, Attributes attributes, long startNanos) {
          Map<String, String> headers = context.get(HEADERS);
          DynamicPolicy policy = policyFrom(context);
          addHeaderSpanAttributes(
              context, headers == null ? Map.of() : headers, policy);
          return context.with(START_NANOS, startNanos);
        }

        @Override
        public void onEnd(Context context, Attributes attributes, long endNanos) {
          Long startNanos = context.get(START_NANOS);
          Map<String, String> headers = context.get(HEADERS);
          DynamicPolicy current = policyFrom(context);
          for (DynamicPolicy.HttpMetricPolicy policy : current.metricPolicies()) {
            if (!policy.enabled || !direction.equals(policy.direction)) {
              continue;
            }
            Double value = metricValue(policy.value, attributes, startNanos, endNanos);
            if (value == null || "COUNTER".equals(policy.instrument) && value < 0) {
              continue;
            }
            String identity = policy.instrument + "|" + policy.unit + "|" + policy.buckets;
            InstrumentHandle handle =
                instruments.compute(
                    policy.name,
                    (name, existing) ->
                        existing == null
                            ? createInstrument(meter, policy, identity)
                            : existing);
            if (handle != null && handle.identity.equals(identity)) {
              handle.recorder.record(value, metricAttributes(policy, attributes, headers));
            }
          }
        }
      };
    }

    private static InstrumentHandle createInstrument(
        Meter meter, DynamicPolicy.HttpMetricPolicy policy, String identity) {
      if ("HISTOGRAM".equals(policy.instrument)) {
        var builder =
            meter
                .histogramBuilder(policy.name)
                .setUnit(policy.unit)
                .setDescription(policy.description);
        if (!policy.buckets.isEmpty()) {
          builder.setExplicitBucketBoundariesAdvice(policy.buckets);
        }
        DoubleHistogram histogram = builder.build();
        return new InstrumentHandle(identity, histogram::record);
      }
      if ("UP_DOWN_COUNTER".equals(policy.instrument)) {
        DoubleUpDownCounter counter =
            meter
                .upDownCounterBuilder(policy.name)
                .ofDoubles()
                .setUnit(policy.unit)
                .setDescription(policy.description)
                .build();
        return new InstrumentHandle(identity, counter::add);
      }
      DoubleCounter counter =
          meter
              .counterBuilder(policy.name)
              .ofDoubles()
              .setUnit(policy.unit)
              .setDescription(policy.description)
              .build();
      return new InstrumentHandle(identity, counter::add);
    }

    private static Double metricValue(
        DynamicPolicy.ValueSource value,
        Attributes attributes,
        Long startNanos,
        long endNanos) {
      if (value == null) {
        return null;
      }
      return switch (value.source) {
        case "DURATION" ->
            startNanos == null || endNanos < startNanos
                ? null
                : (endNanos - startNanos) / 1_000_000_000d;
        case "CONSTANT" -> Double.isFinite(value.constant) ? value.constant : null;
        case "ATTRIBUTE" -> number(attributeValue(attributes, value.path));
        default -> null;
      };
    }

    private static Object attributeValue(Attributes attributes, String name) {
      final Object[] result = new Object[1];
      attributes.forEach(
          (key, value) -> {
            if (key.getKey().equals(name)) {
              result[0] = value;
            }
          });
      return result[0];
    }

    private static Double number(Object value) {
      if (value instanceof Number number) {
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : null;
      }
      try {
        double result = Double.parseDouble(String.valueOf(value));
        return Double.isFinite(result) ? result : null;
      } catch (RuntimeException ignored) {
        return null;
      }
    }

    private static Attributes metricAttributes(
        DynamicPolicy.HttpMetricPolicy policy,
        Attributes source,
        Map<String, String> headers) {
      Map<String, Object> available = new LinkedHashMap<>();
      source.forEach((key, value) -> available.put(key.getKey(), value));
      AttributesBuilder result = Attributes.builder();
      for (String name : policy.standardAttributes) {
        put(result, name, available.get(name));
      }
      for (DynamicPolicy.AttributeSource attribute : policy.customAttributes) {
        String raw =
            headers == null
                ? null
                : headers.get(DynamicPolicy.normalizedHeaderName(attribute.header));
        Object value = transform(raw, attribute.valuePolicy);
        put(result, attribute.attribute, value);
      }
      return result.build();
    }

    private void addHeaderSpanAttributes(
        Context context, Map<String, String> headers, DynamicPolicy current) {
      Span span = Span.fromContext(context);
      if (!span.isRecording()) {
        return;
      }
      for (DynamicPolicy.HttpMetricPolicy policy : current.metricPolicies()) {
        if (!policy.enabled || !direction.equals(policy.direction)) {
          continue;
        }
        for (DynamicPolicy.AttributeSource attribute : policy.customAttributes) {
          if (!attribute.destinations.contains("SPAN")) {
            continue;
          }
          Object value =
              transform(
                  headers.get(DynamicPolicy.normalizedHeaderName(attribute.header)),
                  attribute.valuePolicy);
          setSpanAttribute(span, attribute.attribute, value);
        }
      }
    }

    private static DynamicPolicy policyFrom(Context context) {
      DynamicPolicy policy = context.get(POLICY);
      return policy == null ? PolicyState.current() : policy;
    }
  }

  private record InstrumentHandle(String identity, Recorder recorder) {}

  @FunctionalInterface
  private interface Recorder {
    void record(double value, Attributes attributes);
  }

  public static Object transform(String raw, DynamicPolicy.ValuePolicy policy) {
    if ("PASSTHROUGH".equals(policy.type)) {
      return raw == null || raw.isBlank() ? null : raw;
    }
    if (raw == null || raw.isBlank()) {
      if ("BOOLEAN".equals(policy.type)) {
        return Boolean.parseBoolean(policy.fallback);
      }
      return policy.fallback == null || policy.fallback.isBlank() ? null : policy.fallback;
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
        for (DynamicPolicy.Range range : policy.ranges) {
          if (range.max == null || number <= range.max) {
            return range.label;
          }
        }
      } catch (NumberFormatException ignored) {
        // The configured fallback is used.
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

  public static void put(AttributesBuilder target, String name, Object value) {
    if (value instanceof Boolean booleanValue) {
      target.put(AttributeKey.booleanKey(name), booleanValue);
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      target.put(AttributeKey.longKey(name), ((Number) value).longValue());
    } else if (value instanceof Number number) {
      target.put(AttributeKey.doubleKey(name), number.doubleValue());
    } else if (value != null) {
      target.put(AttributeKey.stringKey(name), truncate(String.valueOf(value), 128));
    }
  }

  public static void setSpanAttribute(Span span, String name, Object value) {
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
      span.setAttribute(name, truncate(String.valueOf(value), 128));
    }
  }

  private static String truncate(String value, int length) {
    return value.substring(0, Math.min(value.length(), length));
  }
}
