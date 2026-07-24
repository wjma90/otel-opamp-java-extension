package dev.o11y.agent.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class DynamicPolicy {
  static final int MAX_POLICY_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = jsonMapper();

  public String schemaVersion = "1.4";
  @JsonProperty
  List<NamedValue> requestHeaders = new ArrayList<>();

  @JsonProperty
  List<NamedValue> responseHeaders = new ArrayList<>();

  @JsonProperty
  List<NamedValue> deniedHeaders = new ArrayList<>();

  @JsonProperty
  List<NamedValue> deniedBodyPaths = new ArrayList<>();

  @JsonProperty
  List<HttpMetricPolicy> metricPolicies = new ArrayList<>();

  @JsonProperty
  List<MethodPolicy> methodPolicies = new ArrayList<>();

  @JsonProperty
  List<BodyEventPolicy> bodyEventPolicies = new ArrayList<>();

  @JsonProperty
  List<EventMetricPolicy> eventMetricPolicies = new ArrayList<>();

  @JsonProperty
  List<MessagingEventPolicy> messagingEventPolicies = new ArrayList<>();

  @JsonProperty
  List<MessagingMetricPolicy> messagingMetricPolicies = new ArrayList<>();

  public List<NamedValue> requestHeaders() {
    return List.copyOf(requestHeaders);
  }

  public List<NamedValue> responseHeaders() {
    return List.copyOf(responseHeaders);
  }

  public List<HttpMetricPolicy> metricPolicies() {
    return List.copyOf(metricPolicies);
  }

  public List<MethodPolicy> methodPolicies() {
    return List.copyOf(methodPolicies);
  }

  public List<BodyEventPolicy> bodyEventPolicies() {
    return List.copyOf(bodyEventPolicies);
  }

  public List<EventMetricPolicy> eventMetricPolicies() {
    return List.copyOf(eventMetricPolicies);
  }

  public List<MessagingEventPolicy> messagingEventPolicies() {
    return List.copyOf(messagingEventPolicies);
  }

  public List<MessagingMetricPolicy> messagingMetricPolicies() {
    return List.copyOf(messagingMetricPolicies);
  }

  public static DynamicPolicy parse(String json) throws Exception {
    validateSourceSize(json);
    DynamicPolicy policy = JSON.readValue(json, DynamicPolicy.class);
    policy.normalize();
    return policy;
  }

  static String toJson(DynamicPolicy policy) throws Exception {
    return JSON.writeValueAsString(policy);
  }

  static void validateSourceSize(String source) {
    if (source == null) {
      throw new IllegalArgumentException("policy document is required");
    }
    if (source.length() > MAX_POLICY_BYTES
        || source.getBytes(StandardCharsets.UTF_8).length > MAX_POLICY_BYTES) {
      throw new IllegalArgumentException("policy document exceeds the 1048576-byte safety limit");
    }
  }

  private static ObjectMapper jsonMapper() {
    ObjectMapper mapper =
        new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxNumberLength(128)
                .maxStringLength(4096)
                .build());
    return mapper;
  }

  private void normalize() {
    requestHeaders = objectList(requestHeaders, NamedValue::new);
    responseHeaders = objectList(responseHeaders, NamedValue::new);
    deniedHeaders = objectList(deniedHeaders, NamedValue::new);
    deniedBodyPaths = objectList(deniedBodyPaths, NamedValue::new);
    metricPolicies = objectList(metricPolicies, HttpMetricPolicy::new);
    methodPolicies = objectList(methodPolicies, MethodPolicy::new);
    bodyEventPolicies = objectList(bodyEventPolicies, BodyEventPolicy::new);
    eventMetricPolicies = objectList(eventMetricPolicies, EventMetricPolicy::new);
    messagingEventPolicies = objectList(messagingEventPolicies, MessagingEventPolicy::new);
    messagingMetricPolicies = objectList(messagingMetricPolicies, MessagingMetricPolicy::new);
    normalizeHeaders(requestHeaders, true);
    normalizeHeaders(responseHeaders, true);
    normalizeHeaders(deniedHeaders, false);
    for (HttpMetricPolicy metric : metricPolicies) {
      metric.id = string(metric.id);
      metric.direction = string(metric.direction);
      metric.name = string(metric.name);
      metric.instrument = string(metric.instrument);
      metric.unit = string(metric.unit);
      metric.description = string(metric.description);
      if (metric.value != null) {
        normalizeValueSource(metric.value);
      }
      metric.standardAttributes = stringList(metric.standardAttributes);
      metric.customAttributes = objectList(metric.customAttributes, AttributeSource::new);
      metric.buckets = list(metric.buckets);
      for (AttributeSource attribute : metric.customAttributes) {
        normalizeValueSource(attribute);
        attribute.header = normalizedHeaderName(attribute.header);
        attribute.attribute = string(attribute.attribute);
        attribute.destinations = stringList(attribute.destinations);
        if (attribute.valuePolicy != null) {
          attribute.valuePolicy.normalize();
        }
      }
    }
    for (MethodPolicy method : methodPolicies) {
      method.id = string(method.id);
      method.packagePrefix = string(method.packagePrefix);
      method.className = string(method.className);
      method.methodName = string(method.methodName);
      method.captures = objectList(method.captures, Capture::new);
      method.metrics = objectList(method.metrics, MethodMetric::new);
      normalizeLog(method.log);
      for (Capture capture : method.captures) {
        normalizeValueSource(capture);
        capture.attribute = string(capture.attribute);
        capture.type = string(capture.type);
        capture.destinations = stringList(capture.destinations);
        if (capture.valuePolicy != null) {
          capture.valuePolicy.normalize();
        }
      }
      for (MethodMetric metric : method.metrics) {
        metric.name = string(metric.name);
        metric.instrument = string(metric.instrument);
        metric.unit = string(metric.unit);
        metric.description = string(metric.description);
        if (metric.value != null) {
          normalizeValueSource(metric.value);
        }
        metric.buckets = list(metric.buckets);
      }
    }
    for (BodyEventPolicy event : bodyEventPolicies) {
      event.id = string(event.id);
      event.ruleName = string(event.ruleName);
      event.direction = string(event.direction);
      event.requestContentType = string(event.requestContentType);
      event.responseContentType = string(event.responseContentType);
      event.eventName = string(event.eventName);
      event.staticAttributes = objectList(event.staticAttributes, StaticAttribute::new);
      for (StaticAttribute attribute : event.staticAttributes) {
        normalizeStaticAttribute(attribute);
      }
      event.fields = objectList(event.fields, BodyField::new);
      event.derivedFields = objectList(event.derivedFields, DerivedField::new);
      event.conditions = objectList(event.conditions, HttpCondition::new);
      for (HttpCondition condition : event.conditions) {
        condition.source = normalizedToken(condition.source);
        condition.operator = normalizedToken(condition.operator);
        condition.path = normalizedHttpSelector(condition.source, condition.path);
        condition.values = stringList(condition.values);
      }
      normalizeLog(event.log);
      for (BodyField field : event.fields) {
        field.attribute = string(field.attribute);
        field.source = normalizedToken(field.source);
        field.path = normalizedHttpSelector(field.source, field.path);
        field.type = normalizedToken(field.type);
        field.destinations = stringList(field.destinations);
        if (field.valuePolicy != null) {
          field.valuePolicy.normalize();
        }
      }
      for (DerivedField field : event.derivedFields) {
        field.attribute = string(field.attribute);
        field.expression = string(field.expression);
        field.type = normalizedToken(field.type);
        field.destinations = stringList(field.destinations);
        if (field.valuePolicy != null) {
          field.valuePolicy.normalize();
        }
      }
    }
    for (EventMetricPolicy metric : eventMetricPolicies) {
      normalizeEventMetric(metric);
      metric.dimensions = stringList(metric.dimensions);
      metric.buckets = list(metric.buckets);
    }
    for (MessagingEventPolicy event : messagingEventPolicies) {
      event.id = string(event.id);
      event.ruleName = string(event.ruleName);
      event.scope = normalizedToken(event.scope);
      event.eventName = string(event.eventName);
      event.staticAttributes = objectList(event.staticAttributes, StaticAttribute::new);
      for (StaticAttribute attribute : event.staticAttributes) {
        normalizeStaticAttribute(attribute);
      }
      event.conditions = objectList(event.conditions, MessagingCondition::new);
      for (MessagingCondition condition : event.conditions) {
        condition.source = normalizedToken(condition.source);
        condition.operator = normalizedToken(condition.operator);
        condition.path = normalizedMessagingSelector(condition.source, condition.path);
        condition.values = stringList(condition.values);
      }
      event.fields = objectList(event.fields, MessagingField::new);
      for (MessagingField field : event.fields) {
        field.attribute = string(field.attribute);
        field.source = normalizedToken(field.source);
        field.path = normalizedMessagingSelector(field.source, field.path);
        field.type = normalizedToken(field.type);
        field.destinations = stringList(field.destinations);
        if (field.valuePolicy != null) {
          field.valuePolicy.normalize();
        }
      }
      normalizeLog(event.log);
    }
    for (MessagingMetricPolicy metric : messagingMetricPolicies) {
      normalizeEventMetric(metric);
      metric.dimensions = stringList(metric.dimensions);
      metric.buckets = list(metric.buckets);
    }
  }

  private static void normalizeValueSource(ValueSource source) {
    source.source = string(source.source);
    source.path = normalizedObjectPath(source.path);
  }

  private static void normalizeStaticAttribute(StaticAttribute attribute) {
    attribute.attribute = string(attribute.attribute);
    attribute.value = string(attribute.value);
    attribute.type = normalizedToken(attribute.type);
    attribute.destinations = stringList(attribute.destinations);
  }

  private static void normalizeEventMetric(EventMetricPolicy metric) {
    metric.id = string(metric.id);
    metric.eventName = string(metric.eventName);
    metric.name = string(metric.name);
    metric.instrument = string(metric.instrument);
    metric.unit = string(metric.unit);
    metric.description = string(metric.description);
    metric.valueField = string(metric.valueField);
    metric.standardAttributes = stringList(metric.standardAttributes);
  }

  private static void normalizeEventMetric(MessagingMetricPolicy metric) {
    metric.id = string(metric.id);
    metric.eventName = string(metric.eventName);
    metric.name = string(metric.name);
    metric.instrument = string(metric.instrument);
    metric.unit = string(metric.unit);
    metric.description = string(metric.description);
    metric.valueField = string(metric.valueField);
  }

  private static void normalizeLog(LogPolicy log) {
    if (log != null) {
      log.severity = string(log.severity);
    }
  }

  private static <T> List<T> list(List<T> value) {
    return value == null ? new ArrayList<>() : value;
  }

  private static <T> List<T> objectList(List<T> value, Supplier<T> defaultValue) {
    List<T> normalized = list(value);
    for (int index = 0; index < normalized.size(); index++) {
      if (normalized.get(index) == null) {
        normalized.set(index, defaultValue.get());
      }
    }
    return normalized;
  }

  private static List<String> stringList(List<String> value) {
    List<String> normalized = list(value);
    for (int index = 0; index < normalized.size(); index++) {
      normalized.set(index, string(normalized.get(index)));
    }
    return normalized;
  }

  private static String string(String value) {
    return value == null ? "" : value;
  }

  private static void normalizeHeaders(List<NamedValue> values, boolean directional) {
    for (NamedValue value : values) {
      value.name = normalizedHeaderName(value.name);
      if (directional) {
        value.direction = normalizedDirection(value.direction);
      }
    }
  }

  private static String normalizedDirection(String value) {
    return value == null || value.isBlank() ? "INCOMING" : value.trim().toUpperCase(Locale.ROOT);
  }

  static String normalizedObjectPath(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizedHeaderName(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizedToken(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizedHttpSelector(String source, String value) {
    if ("REQUEST_HEADER".equals(source) || "RESPONSE_HEADER".equals(source)) {
      return normalizedHeaderName(value);
    }
    return value == null ? "" : value.trim();
  }

  private static String normalizedMessagingSelector(String source, String value) {
    String selector = value == null ? "" : value.trim();
    return "MESSAGE_HEADER".equals(source) ? selector.toLowerCase(Locale.ROOT) : selector;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class NamedValue {
    public String name = "";
    public String direction = "INCOMING";
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class HttpMetricPolicy {
    public String id = "";
    public boolean enabled = true;
    public String direction = "INCOMING";
    public ValueSource value = new ValueSource("DURATION");
    public String name = "";
    public String instrument = "HISTOGRAM";
    public String unit = "s";
    public String description = "";
    public List<String> standardAttributes = new ArrayList<>();
    public List<AttributeSource> customAttributes = new ArrayList<>();
    public List<Double> buckets = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class ValueSource {
    public String source = "CONSTANT";
    public int argumentIndex = -1;
    public String path = "";
    public double constant = 1;

    public ValueSource() {}

    public ValueSource(String source) {
      this.source = source;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class AttributeSource extends ValueSource {
    public String header = "";
    public String attribute = "";
    public List<String> destinations = new ArrayList<>();
    public ValuePolicy valuePolicy = new ValuePolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class ValuePolicy {
    public String type = "ENUM";
    public List<String> allowed = new ArrayList<>();
    public String fallback = "OTHER";
    public List<Range> ranges = new ArrayList<>();

    void normalize() {
      type = normalizedToken(type);
      allowed = stringList(allowed);
      ranges = objectList(ranges, Range::new);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class Range {
    public Double max;
    public String label = "";
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MethodPolicy {
    public String id = "";
    public boolean enabled = true;
    public String packagePrefix = "";
    public String className = "";
    public String methodName = "";
    public List<Capture> captures = new ArrayList<>();
    public List<MethodMetric> metrics = new ArrayList<>();
    public LogPolicy log = new LogPolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class Capture extends ValueSource {
    public String attribute = "";
    public String type = "STRING";
    public List<String> destinations = new ArrayList<>();
    public ValuePolicy valuePolicy = new ValuePolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MethodMetric {
    public String name = "";
    public String instrument = "COUNTER";
    public String unit = "1";
    public String description = "";
    public ValueSource value = new ValueSource();
    public List<Double> buckets = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class BodyEventPolicy {
    public String id = "";
    public boolean enabled = true;
    public String ruleName = "";
    public String direction = "INCOMING";
    public String requestContentType = "application/json";
    public String responseContentType = "application/json";
    public List<HttpCondition> conditions = new ArrayList<>();
    public String eventName = "";
    public List<StaticAttribute> staticAttributes = new ArrayList<>();
    public int maxBodyBytes = 65536;
    public List<BodyField> fields = new ArrayList<>();
    public List<DerivedField> derivedFields = new ArrayList<>();
    public LogPolicy log = new LogPolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class StaticAttribute {
    public String attribute = "";
    public String value = "";
    public String type = "STRING";
    public List<String> destinations = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class HttpCondition {
    public String source = "REQUEST_PATH";
    public String path = "";
    public String operator = "EQUALS";
    public List<String> values = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class BodyField {
    public String attribute = "";
    public String source = "REQUEST_BODY";
    public String path = "";
    public String type = "STRING";
    public List<String> destinations = new ArrayList<>();
    public ValuePolicy valuePolicy = new ValuePolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class DerivedField {
    public String attribute = "";
    public String expression = "";
    public String type = "DOUBLE";
    public List<String> destinations = new ArrayList<>();
    public ValuePolicy valuePolicy = new ValuePolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class EventMetricPolicy {
    public String id = "";
    public boolean enabled = true;
    public String eventName = "";
    public String name = "";
    public String instrument = "COUNTER";
    public String unit = "1";
    public String description = "";
    public String valueField = "";
    public List<String> dimensions = new ArrayList<>();
    public List<String> standardAttributes = new ArrayList<>();
    public List<Double> buckets = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MessagingEventPolicy {
    public String id = "";
    public boolean enabled = true;
    public String ruleName = "";
    public String scope = "KAFKA_PRODUCER";
    public List<MessagingCondition> conditions = new ArrayList<>();
    public String eventName = "";
    public List<StaticAttribute> staticAttributes = new ArrayList<>();
    public int maxPayloadBytes = 65536;
    public List<MessagingField> fields = new ArrayList<>();
    public LogPolicy log = new LogPolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MessagingCondition {
    public String source = "DESTINATION";
    public String path = "";
    public String operator = "EQUALS";
    public List<String> values = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MessagingField {
    public String attribute = "";
    public String source = "PAYLOAD";
    public String path = "";
    public String type = "STRING";
    public List<String> destinations = new ArrayList<>();
    public ValuePolicy valuePolicy = new ValuePolicy();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class MessagingMetricPolicy {
    public String id = "";
    public boolean enabled = true;
    public String eventName = "";
    public String name = "";
    public String instrument = "COUNTER";
    public String unit = "1";
    public String description = "";
    public String valueField = "";
    public List<String> dimensions = new ArrayList<>();
    public List<Double> buckets = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static final class LogPolicy {
    public boolean enabled;
    public String severity = "INFO";
    public String body = "Dynamic method policy captured";
  }
}
