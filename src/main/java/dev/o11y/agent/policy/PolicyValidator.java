package dev.o11y.agent.policy;

import dev.o11y.agent.method.discovery.ApplicationPackageResolver;
import dev.o11y.agent.policy.DynamicPolicy.AttributeSource;
import dev.o11y.agent.policy.DynamicPolicy.BodyEventPolicy;
import dev.o11y.agent.policy.DynamicPolicy.BodyField;
import dev.o11y.agent.policy.DynamicPolicy.Capture;
import dev.o11y.agent.policy.DynamicPolicy.DerivedField;
import dev.o11y.agent.policy.DynamicPolicy.EventMetricPolicy;
import dev.o11y.agent.policy.DynamicPolicy.HttpMetricPolicy;
import dev.o11y.agent.policy.DynamicPolicy.HttpCondition;
import dev.o11y.agent.policy.DynamicPolicy.MethodMetric;
import dev.o11y.agent.policy.DynamicPolicy.MethodPolicy;
import dev.o11y.agent.policy.DynamicPolicy.MessagingCondition;
import dev.o11y.agent.policy.DynamicPolicy.MessagingEventPolicy;
import dev.o11y.agent.policy.DynamicPolicy.MessagingField;
import dev.o11y.agent.policy.DynamicPolicy.MessagingMetricPolicy;
import dev.o11y.agent.policy.DynamicPolicy.NamedValue;
import dev.o11y.agent.policy.DynamicPolicy.StaticAttribute;
import dev.o11y.agent.policy.DynamicPolicy.ValuePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PolicyValidator {
  public static final String MAX_SUPPORTED_SCHEMA_VERSION = "1.6";
  private static final Set<String> SUPPORTED_SCHEMA_VERSIONS =
      Set.of("1.0", "1.1", "1.2", "1.3", "1.4", "1.5", MAX_SUPPORTED_SCHEMA_VERSION);
  static final int MAX_HTTP_METRIC_POLICIES = 64;
  static final int MAX_METHOD_POLICIES = 64;
  static final int MAX_METHOD_CAPTURES_PER_POLICY = 32;
  static final int MAX_METHOD_METRICS_PER_POLICY = 16;
  static final int MAX_EXPLICIT_BUCKETS = 64;
  static final int MAX_LIFETIME_INSTRUMENT_IDENTITIES = 256;
  static final int MAX_METRIC_DIMENSIONS = 8;
  static final int MAX_METRIC_CARDINALITY = 4096;
  private static final Pattern METRIC_NAME = Pattern.compile("[a-z][a-z0-9_.-]{2,127}");
  private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[a-z][a-z0-9_.-]{1,95}");
  private static final Pattern JAVA_NAME =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
  private static final Pattern METHOD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Pattern PATH = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
  private static final Pattern JSON_PATH =
      Pattern.compile("\\$?(?:\\.?[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]{1,4}]){1,16}");
  private static final Pattern HEADER_NAME =
      Pattern.compile("[a-z0-9!#$%&'*+.^_`|~-]{1,128}");
  private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
  private static final Pattern PATH_PARAMETER_NAME =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
  private static final Pattern MESSAGE_PROPERTY_NAME =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,127}");
  private static final Pattern EVENT_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{1,127}");
  private static final Set<String> BUILT_INS =
      Set.of(
          "http.server.request.duration",
          "http.server.active_requests",
          "http.server.request.body.size",
          "http.server.response.body.size",
          "http.client.request.duration",
          "http.client.request.body.size",
          "http.client.response.body.size");
  private static final Set<String> STANDARD_HTTP_ATTRIBUTES =
      Set.of(
          "http.request.method",
          "url.scheme",
          "error.type",
          "http.response.status_code",
          "http.route",
          "network.protocol.name",
          "network.protocol.version",
          "server.address",
          "server.port",
          "user_agent.synthetic.type");
  private static final Set<String> EVENT_HTTP_ATTRIBUTES =
      Set.of(
          "http.request.method",
          "error.type",
          "http.response.status_code",
          "http.route");
  private static final Set<String> NUMERIC_HTTP_ATTRIBUTES =
      Set.of("http.response.status_code", "server.port");
  private static final Map<String, String> LOCKED_IDENTITIES = new ConcurrentHashMap<>();

  private PolicyValidator() {}

  public static void validateAndLock(DynamicPolicy policy) {
    validateAndLock(policy, allowedPackages());
  }

  static synchronized void validateAndLock(
      DynamicPolicy policy, List<String> allowedPackages) {
    List<String> errors = validate(policy, allowedPackages);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }

    Map<String, String> proposed = identities(policy);
    for (Map.Entry<String, String> entry : proposed.entrySet()) {
      String previous = LOCKED_IDENTITIES.get(entry.getKey());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException(
            "metric identity is immutable after creation: " + entry.getKey());
      }
    }
    long additionalIdentities =
        proposed.keySet().stream().filter(name -> !LOCKED_IDENTITIES.containsKey(name)).count();
    if (LOCKED_IDENTITIES.size() + additionalIdentities
        > MAX_LIFETIME_INSTRUMENT_IDENTITIES) {
      throw new IllegalArgumentException(
          "metric instrument identities exceed the lifetime limit of "
              + MAX_LIFETIME_INSTRUMENT_IDENTITIES);
    }
    LOCKED_IDENTITIES.putAll(proposed);
  }

  static synchronized void resetLockedIdentitiesForTest() {
    LOCKED_IDENTITIES.clear();
  }

  static synchronized int lockedIdentityCountForTest() {
    return LOCKED_IDENTITIES.size();
  }

  static List<String> validate(DynamicPolicy policy) {
    return validate(policy, allowedPackages());
  }

  static List<String> validate(DynamicPolicy policy, List<String> allowedPackages) {
    List<String> errors = new ArrayList<>();
    List<String> methodPackages = normalizeAllowedPackages(allowedPackages);
    String schemaVersion = policy.schemaVersion == null ? "" : policy.schemaVersion;
    if (!SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
      errors.add("schemaVersion must be 1.0, 1.1, 1.2, 1.3, 1.4, 1.5 or 1.6");
    }
    if (!policy.metricPolicies.isEmpty()
        && !Set.of("1.3", "1.4", "1.5", "1.6").contains(schemaVersion)) {
      errors.add("dynamic HTTP metric value sources require schemaVersion 1.3 or newer");
    }
    if ((!policy.bodyEventPolicies.isEmpty() || !policy.eventMetricPolicies.isEmpty())
        && !Set.of("1.3", "1.4", "1.5", "1.6").contains(schemaVersion)) {
      errors.add("policy-driven HTTP events require schemaVersion 1.3 or newer");
    }
    if (policy.bodyEventPolicies.stream().anyMatch(event -> !event.derivedFields.isEmpty())
        && !Set.of("1.2", "1.3", "1.4", "1.5", "1.6").contains(schemaVersion)) {
      errors.add("derived body fields require schemaVersion 1.2 or newer");
    }
    if (usesHttpMetadataSources(policy)
        && !Set.of("1.4", "1.5", "1.6").contains(schemaVersion)) {
      errors.add(
          "REQUEST_HEADER, RESPONSE_HEADER and REQUEST_QUERY require schemaVersion 1.4");
    }
    if (usesPathParameterSource(policy) && !Set.of("1.5", "1.6").contains(schemaVersion)) {
      errors.add("REQUEST_PATH_PARAM requires schemaVersion 1.5");
    }
    if ((!policy.messagingEventPolicies.isEmpty()
            || !policy.messagingMetricPolicies.isEmpty())
        && !Set.of("1.5", "1.6").contains(schemaVersion)) {
      errors.add("messaging policy events require schemaVersion 1.5");
    }
    if (policy.eventMetricPolicies.stream()
            .anyMatch(metric -> !metric.standardAttributes.isEmpty())
        && !"1.6".equals(schemaVersion)) {
      errors.add("HTTP event metric standard attributes require schemaVersion 1.6");
    }
    validateHeaderList("requestHeaders", policy.requestHeaders, 16, true, errors);
    validateHeaderList("responseHeaders", policy.responseHeaders, 16, true, errors);
    validateHeaderList("deniedHeaders", policy.deniedHeaders, 64, false, errors);
    validateDeniedBodyPaths(policy.deniedBodyPaths, errors);
    if (policy.metricPolicies.size() > MAX_HTTP_METRIC_POLICIES) {
      errors.add(
          "metricPolicies exceeds its limit of " + MAX_HTTP_METRIC_POLICIES);
    }
    if (policy.methodPolicies.size() > MAX_METHOD_POLICIES) {
      errors.add("methodPolicies exceeds its limit of " + MAX_METHOD_POLICIES);
    }

    Set<String> names = new HashSet<>();
    Set<String> normalizedNames = new HashSet<>();
    Set<String> policyIds = new HashSet<>();
    for (HttpMetricPolicy metric : policy.metricPolicies) {
      if (!metric.enabled) {
        continue;
      }
      if (metric.id == null || metric.id.isBlank() || !policyIds.add(metric.id)) {
        errors.add("HTTP metric policies require unique IDs");
      }
      if (!Set.of("INCOMING", "OUTGOING").contains(metric.direction)) {
        errors.add(metric.id + ": direction must be INCOMING or OUTGOING");
      }
      validateMetric(
          metric.name,
          metric.instrument,
          metric.unit,
          metric.buckets,
          names,
          normalizedNames,
          errors);
      validateHttpMetricValue(metric, errors);
      if (metric.standardAttributes.size() > STANDARD_HTTP_ATTRIBUTES.size()) {
        errors.add(
            metric.name
                + ": standardAttributes exceeds its limit of "
                + STANDARD_HTTP_ATTRIBUTES.size());
      }
      Set<String> standardDimensions = new HashSet<>();
      for (String attribute : metric.standardAttributes) {
        if (!STANDARD_HTTP_ATTRIBUTES.contains(attribute)) {
          errors.add(metric.name + ": unsupported standard attribute " + attribute);
        } else if (!standardDimensions.add(attribute)) {
          errors.add(metric.name + ": duplicated standard attribute " + attribute);
        }
      }
      if (metric.customAttributes.size() > MAX_METRIC_DIMENSIONS) {
        errors.add(
            metric.name
                + ": customAttributes exceeds its limit of "
                + MAX_METRIC_DIMENSIONS);
      }
      Set<String> customDimensions = new HashSet<>();
      long customCardinality = 1;
      for (AttributeSource attribute : metric.customAttributes) {
        validateAttribute(attribute.attribute, errors);
        if (!customDimensions.add(attribute.attribute)) {
          errors.add(metric.name + ": duplicated custom attribute " + attribute.attribute);
        }
        if (!"REQUEST_HEADER".equals(attribute.source)) {
          errors.add(metric.name + ": only REQUEST_HEADER is supported for HTTP custom labels");
        }
        String header = DynamicPolicy.normalizedHeaderName(attribute.header);
        if (!header.matches("[a-z0-9!#$%&'*+.^_`|~-]{1,128}")) {
          errors.add(metric.name + ": invalid metric header " + header);
        }
        for (String destination : attribute.destinations) {
          if (!"SPAN".equals(destination)) {
            errors.add(metric.name + ": HTTP custom attributes support only SPAN destination");
          }
        }
        validateBoundedValuePolicy(attribute.valuePolicy, metric.name, errors);
        customCardinality =
            boundedCardinalityProduct(customCardinality, valueCardinality(attribute.valuePolicy));
      }
      if (customCardinality > MAX_METRIC_CARDINALITY) {
        errors.add(
            metric.name
                + ": custom attribute cardinality exceeds its limit of "
                + MAX_METRIC_CARDINALITY);
      }
    }

    for (MethodPolicy method : policy.methodPolicies) {
      if (method.captures.size() > MAX_METHOD_CAPTURES_PER_POLICY) {
        errors.add(
            method.id
                + ": captures exceeds its limit of "
                + MAX_METHOD_CAPTURES_PER_POLICY);
      }
      if (method.metrics.size() > MAX_METHOD_METRICS_PER_POLICY) {
        errors.add(
            method.id
                + ": metrics exceeds its limit of "
                + MAX_METHOD_METRICS_PER_POLICY);
      }
      if (!method.enabled) {
        continue;
      }
      if (method.id == null || method.id.isBlank() || !policyIds.add(method.id)) {
        errors.add("method policies require unique IDs");
      }
      if (!JAVA_NAME.matcher(method.packagePrefix).matches()) {
        errors.add(method.id + ": invalid packagePrefix");
      } else if (methodPackages.isEmpty()) {
        errors.add(
            method.id
                + ": method capture is disabled because no safe application package "
                + "was discovered; use O11Y_METHOD_PACKAGES only when automatic "
                + "discovery is unavailable");
      } else if (!isAllowedPackage(method.packagePrefix, methodPackages)) {
        errors.add(method.id + ": packagePrefix is outside o11y.method.packages");
      }
      if (!JAVA_NAME.matcher(method.className).matches()
          || !method.className.startsWith(method.packagePrefix + ".")) {
        errors.add(method.id + ": className must belong to packagePrefix");
      }
      if (!METHOD_NAME.matcher(method.methodName).matches()) {
        errors.add(method.id + ": invalid methodName");
      }
      Set<String> captureAttributes = new HashSet<>();
      int metricDimensions = 0;
      long metricCardinality = 1;
      for (Capture capture : method.captures) {
        validateSource(capture, method.id, errors);
        validateAttribute(capture.attribute, errors);
        if (!captureAttributes.add(capture.attribute)) {
          errors.add(method.id + ": duplicated capture attribute " + capture.attribute);
        }
        if (!Set.of("STRING", "DOUBLE", "LONG", "BOOLEAN").contains(capture.type)) {
          errors.add(method.id + ": unsupported capture type " + capture.type);
        }
        for (String destination : capture.destinations) {
          if (!Set.of("SPAN", "LOG", "METRIC").contains(destination)) {
            errors.add(method.id + ": unsupported destination " + destination);
          }
        }
        if (capture.destinations.contains("METRIC")) {
          metricDimensions++;
          validateBoundedValuePolicy(capture.valuePolicy, method.id, errors);
          metricCardinality =
              boundedCardinalityProduct(metricCardinality, valueCardinality(capture.valuePolicy));
        } else if (capture.valuePolicy == null) {
          errors.add(method.id + "/" + capture.attribute + ": valuePolicy must be an object");
        }
      }
      if (metricDimensions > MAX_METRIC_DIMENSIONS) {
        errors.add(
            method.id
                + ": metric dimensions exceeds its limit of "
                + MAX_METRIC_DIMENSIONS);
      }
      if (metricCardinality > MAX_METRIC_CARDINALITY) {
        errors.add(
            method.id
                + ": metric dimension cardinality exceeds its limit of "
                + MAX_METRIC_CARDINALITY);
      }
      for (MethodMetric metric : method.metrics) {
        validateMetric(
            metric.name,
            metric.instrument,
            metric.unit,
            metric.buckets,
            names,
            normalizedNames,
            errors);
        validateSource(metric.value, method.id + "/" + metric.name, errors);
      }
      validateLog(method.id, method.log, errors);
    }
    validateBodyEvents(
        policy,
        names,
        normalizedNames,
        policyIds,
        errors);
    validateMessagingEvents(policy, names, normalizedNames, policyIds, errors);
    return errors;
  }

  private static void validateHttpMetricValue(
      HttpMetricPolicy metric, List<String> errors) {
    if (metric.value == null) {
      errors.add(metric.name + ": value is required");
      return;
    }
    switch (metric.value.source) {
      case "DURATION" -> {
        if (!metric.value.path.isBlank()) {
          errors.add(metric.name + ": DURATION does not use path");
        }
      }
      case "CONSTANT" -> {
        if (!Double.isFinite(metric.value.constant) || !metric.value.path.isBlank()) {
          errors.add(metric.name + ": CONSTANT requires a finite value and no path");
        }
      }
      case "ATTRIBUTE" -> {
        if (!NUMERIC_HTTP_ATTRIBUTES.contains(metric.value.path)) {
          errors.add(metric.name + ": ATTRIBUTE must reference a numeric HTTP attribute");
        }
      }
      default -> errors.add(metric.name + ": unsupported HTTP value source " + metric.value.source);
    }
  }

  private static boolean usesHttpMetadataSources(DynamicPolicy policy) {
    Set<String> sources = Set.of("REQUEST_HEADER", "RESPONSE_HEADER", "REQUEST_QUERY");
    return policy.bodyEventPolicies.stream()
        .filter(event -> event.enabled)
        .anyMatch(
            event ->
                event.conditions.stream().anyMatch(condition -> sources.contains(condition.source))
                    || event.fields.stream().anyMatch(field -> sources.contains(field.source)));
  }

  private static boolean usesPathParameterSource(DynamicPolicy policy) {
    return policy.bodyEventPolicies.stream()
        .filter(event -> event.enabled)
        .anyMatch(
            event ->
                event.conditions.stream()
                        .anyMatch(condition -> "REQUEST_PATH_PARAM".equals(condition.source))
                    || event.fields.stream()
                        .anyMatch(field -> "REQUEST_PATH_PARAM".equals(field.source)));
  }

  private static void validateStaticAttributes(
      BodyEventPolicy event, List<String> errors) {
    if (event.staticAttributes.size() > 16) {
      errors.add(event.id + ": staticAttributes exceeds its limit of 16");
      return;
    }
    Set<String> names = new HashSet<>();
    for (StaticAttribute attribute : event.staticAttributes) {
      validateAttribute(attribute.attribute, errors);
      if (!names.add(attribute.attribute)) {
        errors.add(event.id + ": duplicated static attribute " + attribute.attribute);
      }
      if (!Set.of("STRING", "DOUBLE", "LONG", "BOOLEAN").contains(attribute.type)) {
        errors.add(event.id + ": unsupported static attribute type " + attribute.type);
      } else if (!validStaticValue(attribute.value, attribute.type)) {
        errors.add(event.id + ": invalid " + attribute.type + " value for " + attribute.attribute);
      }
      if (attribute.destinations.isEmpty()) {
        errors.add(event.id + ": static attributes require at least one destination");
      }
      for (String destination : attribute.destinations) {
        if (!Set.of("SPAN", "LOG").contains(destination)) {
          errors.add(event.id + ": static attributes support SPAN or LOG destination");
        }
      }
    }
  }

  private static boolean validStaticValue(String value, String type) {
    if (value == null || value.isBlank() || value.length() > 256) {
      return false;
    }
    try {
      return switch (type) {
        case "DOUBLE" -> Double.isFinite(Double.parseDouble(value));
        case "LONG" -> {
          Long.parseLong(value);
          yield true;
        }
        case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
        default -> true;
      };
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static void validateBodyEvents(
      DynamicPolicy policy,
      Set<String> names,
      Set<String> normalizedNames,
      Set<String> policyIds,
      List<String> errors) {
    if (policy.bodyEventPolicies.size() > 16) {
      errors.add("bodyEventPolicies exceeds its limit of 16");
      return;
    }
    if (policy.eventMetricPolicies.size() > 32) {
      errors.add("eventMetricPolicies exceeds its limit of 32");
      return;
    }

    Map<String, Map<String, EventField>> eventFields = new HashMap<>();
    Set<String> requestHeaderSelectors = new HashSet<>();
    Set<String> responseHeaderSelectors = new HashSet<>();
    Set<String> requestQuerySelectors = new HashSet<>();
    Set<String> requestPathParameterSelectors = new HashSet<>();
    Set<String> declaredEventNames = new HashSet<>();
    Set<String> enabledMetricEvents = new HashSet<>();
    for (EventMetricPolicy metric : policy.eventMetricPolicies) {
      if (metric.enabled) {
        enabledMetricEvents.add(metric.eventName);
      }
    }
    for (BodyEventPolicy event : policy.bodyEventPolicies) {
      if (event.eventName != null
          && !event.eventName.isBlank()
          && !declaredEventNames.add(event.eventName)) {
        errors.add(
            event.id
                + ": eventName must be unique across enabled and disabled HTTP event rules");
      }
    }
    for (BodyEventPolicy event : policy.bodyEventPolicies) {
      if (!event.enabled) {
        continue;
      }
      if (event.id == null || event.id.isBlank() || !policyIds.add(event.id)) {
        errors.add("body event policies require unique IDs");
      }
      if (event.ruleName == null || event.ruleName.isBlank() || event.ruleName.length() > 128) {
        errors.add(event.id + ": ruleName is required and limited to 128 characters");
      }
      if (!Set.of("INCOMING", "OUTGOING").contains(event.direction)) {
        errors.add(event.id + ": direction must be INCOMING or OUTGOING");
      }
      if ("OUTGOING".equals(event.direction)
          && (event.conditions.stream()
                  .anyMatch(condition -> "REQUEST_PATH_PARAM".equals(condition.source))
              || event.fields.stream()
                  .anyMatch(field -> "REQUEST_PATH_PARAM".equals(field.source)))) {
        errors.add(event.id + ": REQUEST_PATH_PARAM is supported only for INCOMING HTTP");
      }
      validateJsonContentType(event.id, "requestContentType", event.requestContentType, errors);
      validateJsonContentType(event.id, "responseContentType", event.responseContentType, errors);
      validateHttpConditions(event, errors);
      validatePathParameterSelectors(event, errors);
      if (!eventValue(event.eventName)) {
        errors.add(event.id + ": eventName must be a stable identifier");
      }
      validateStaticAttributes(event, errors);
      if (event.maxBodyBytes < 1024 || event.maxBodyBytes > 262144) {
        errors.add(event.id + ": maxBodyBytes must be between 1024 and 262144");
      }
      if (event.fields.size() > 32) {
        errors.add(event.id + ": fields exceeds its limit of 32 entries");
        continue;
      }
      Map<String, EventField> fields = new HashMap<>();
      Set<String> paths = new HashSet<>();
      for (BodyField field : event.fields) {
        validateAttribute(field.attribute, errors);
        if (!fields.containsKey(field.attribute)) {
          fields.put(
              field.attribute,
              new EventField(field.type, field.destinations, field.valuePolicy));
        } else {
          errors.add(event.id + ": duplicated body field " + field.attribute);
        }
        if (!Set.of(
                "REQUEST_BODY",
                "RESPONSE_BODY",
                "REQUEST_HEADER",
                "RESPONSE_HEADER",
                "REQUEST_QUERY",
                "REQUEST_PATH_PARAM")
            .contains(field.source)) {
          errors.add(event.id + ": unsupported HTTP field source " + field.source);
        }
        if (!validHttpSelector(field.source, field.path)) {
          errors.add(event.id + ": invalid selector for " + field.source + ": " + field.path);
        } else if (!paths.add(
            field.source + "|" + normalizedHttpSelector(field.source, field.path))) {
          errors.add(
              event.id
                  + (Set.of("REQUEST_BODY", "RESPONSE_BODY").contains(field.source)
                      ? ": duplicated JSON path "
                      : ": duplicated HTTP selector ")
                  + field.path);
        }
        if (!Set.of("STRING", "DOUBLE", "LONG", "BOOLEAN").contains(field.type)) {
          errors.add(event.id + ": unsupported body field type " + field.type);
        }
        if (field.destinations.isEmpty()) {
          errors.add(event.id + ": body fields require at least one destination");
        }
        for (String destination : field.destinations) {
          if (!Set.of("SPAN", "LOG", "METRIC").contains(destination)) {
            errors.add(event.id + ": unsupported body destination " + destination);
          }
        }
        if (field.destinations.contains("METRIC")) {
          validateBoundedValuePolicy(field.valuePolicy, event.id + "/" + field.attribute, errors);
        } else if (field.valuePolicy == null) {
          errors.add(event.id + "/" + field.attribute + ": valuePolicy must be an object");
        }
      }
      if (event.derivedFields.size() > 16) {
        errors.add(event.id + ": derivedFields exceeds its limit of 16");
      }
      Set<String> numericFields = new HashSet<>();
      fields.forEach(
          (attribute, definition) -> {
            if (Set.of("DOUBLE", "LONG").contains(definition.type())) {
              numericFields.add(attribute);
            }
          });
      for (DerivedField field : event.derivedFields) {
        validateAttribute(field.attribute, errors);
        if (fields.containsKey(field.attribute)) {
          errors.add(event.id + ": duplicated extracted or derived field " + field.attribute);
          continue;
        }
        if (!"DOUBLE".equals(field.type)) {
          errors.add(event.id + ": derived fields support only DOUBLE");
        }
        if (field.destinations.isEmpty()) {
          errors.add(event.id + ": derived fields require at least one destination");
        }
        for (String destination : field.destinations) {
          if (!Set.of("SPAN", "LOG", "METRIC").contains(destination)) {
            errors.add(event.id + ": unsupported derived destination " + destination);
          }
        }
        if (field.destinations.contains("METRIC")) {
          validateBoundedValuePolicy(
              field.valuePolicy, event.id + "/" + field.attribute, errors);
        } else if (field.valuePolicy == null) {
          errors.add(event.id + "/" + field.attribute + ": valuePolicy must be an object");
        }
        try {
          NumericExpression.compile(field.expression, numericFields);
        } catch (IllegalArgumentException error) {
          errors.add(event.id + "/" + field.attribute + ": " + error.getMessage());
        }
        fields.put(
            field.attribute,
            new EventField(field.type, field.destinations, field.valuePolicy));
        numericFields.add(field.attribute);
      }
      validateLog(event.id, event.log, errors);
      if (!bodyEventHasEffectiveOutput(event, enabledMetricEvents)) {
        errors.add(
            event.id
                + ": must define at least one effective output: SPAN, enabled log, or enabled event metric");
      }
      collectHttpSelectors(
          event,
          requestHeaderSelectors,
          responseHeaderSelectors,
          requestQuerySelectors,
          requestPathParameterSelectors);
      eventFields.put(event.eventName, fields);
    }

    validateSelectorLimit("REQUEST_HEADER", requestHeaderSelectors, errors);
    validateSelectorLimit("RESPONSE_HEADER", responseHeaderSelectors, errors);
    validateSelectorLimit("REQUEST_QUERY", requestQuerySelectors, errors);
    validateSelectorLimit("REQUEST_PATH_PARAM", requestPathParameterSelectors, errors);

    for (EventMetricPolicy metric : policy.eventMetricPolicies) {
      if (!metric.enabled) {
        continue;
      }
      if (metric.id == null || metric.id.isBlank() || !policyIds.add(metric.id)) {
        errors.add("event metric policies require unique IDs");
      }
      validateMetric(
          metric.name,
          metric.instrument,
          metric.unit,
          metric.buckets,
          names,
          normalizedNames,
          errors);
      if (!Set.of("COUNTER", "HISTOGRAM").contains(metric.instrument)) {
        errors.add(metric.name + ": body event metrics support COUNTER or HISTOGRAM");
      }
      Map<String, EventField> fields = eventFields.get(metric.eventName);
      if (fields == null) {
        errors.add(metric.name + ": eventName does not reference an enabled body event");
        continue;
      }
      if (metric.valueField == null || metric.valueField.isBlank()) {
        if (!"COUNTER".equals(metric.instrument)) {
          errors.add(metric.name + ": a valueField is required for a value metric");
        }
      } else {
        EventField value = fields.get(metric.valueField);
        if (value == null || !Set.of("DOUBLE", "LONG").contains(value.type())) {
          errors.add(metric.name + ": valueField must reference a numeric extracted or derived field");
        }
      }
      if (metric.dimensions.size() > 8) {
        errors.add(metric.name + ": dimensions exceeds its limit of 8");
      }
      if (metric.standardAttributes.size() > EVENT_HTTP_ATTRIBUTES.size()) {
        errors.add(
            metric.name
                + ": standardAttributes exceeds its limit of "
                + EVENT_HTTP_ATTRIBUTES.size());
      }
      Set<String> standardAttributes = new HashSet<>();
      BodyEventPolicy owner =
          policy.bodyEventPolicies.stream()
              .filter(event -> event.enabled && event.eventName.equals(metric.eventName))
              .findFirst()
              .orElse(null);
      for (String attribute : metric.standardAttributes) {
        if (!EVENT_HTTP_ATTRIBUTES.contains(attribute)) {
          errors.add(metric.name + ": unsupported HTTP event standard attribute " + attribute);
        } else if (!standardAttributes.add(attribute)) {
          errors.add(metric.name + ": duplicated standard attribute " + attribute);
        } else if ("http.route".equals(attribute)
            && owner != null
            && !"INCOMING".equals(owner.direction)) {
          errors.add(metric.name + ": http.route is supported only for INCOMING HTTP events");
        }
      }
      Set<String> dimensions = new HashSet<>();
      long metricCardinality = 1;
      for (String dimension : metric.dimensions) {
        EventField field = fields.get(dimension);
        if (!dimensions.add(dimension)
            || field == null
            || !field.destinations().contains("METRIC")) {
          errors.add(
              metric.name
                  + ": dimension must reference a unique bounded extracted or derived field: "
                  + dimension);
        } else {
          metricCardinality =
              boundedCardinalityProduct(
                  metricCardinality, valueCardinality(field.valuePolicy()));
        }
      }
      if (metricCardinality > MAX_METRIC_CARDINALITY) {
        errors.add(
            metric.name
                + ": dimension cardinality exceeds its limit of "
                + MAX_METRIC_CARDINALITY);
      }
    }
  }

  private static boolean bodyEventHasEffectiveOutput(
      BodyEventPolicy event, Set<String> enabledMetricEvents) {
    if ((event.log != null && event.log.enabled)
        || enabledMetricEvents.contains(event.eventName)) {
      return true;
    }
    if (event.staticAttributes.stream()
        .anyMatch(attribute -> attribute.destinations.contains("SPAN"))) {
      return true;
    }
    if (event.fields.stream().anyMatch(field -> field.destinations.contains("SPAN"))) {
      return true;
    }
    return event.derivedFields.stream().anyMatch(field -> field.destinations.contains("SPAN"));
  }

  private static void validateMessagingEvents(
      DynamicPolicy policy,
      Set<String> names,
      Set<String> normalizedNames,
      Set<String> policyIds,
      List<String> errors) {
    if (policy.messagingEventPolicies.size() > 32) {
      errors.add("messagingEventPolicies exceeds its limit of 32");
      return;
    }
    if (policy.messagingMetricPolicies.size() > 64) {
      errors.add("messagingMetricPolicies exceeds its limit of 64");
      return;
    }

    Set<String> declaredEventNames = new HashSet<>();
    for (BodyEventPolicy event : policy.bodyEventPolicies) {
      if (event.eventName != null && !event.eventName.isBlank()) {
        declaredEventNames.add(event.eventName);
      }
    }
    for (MessagingEventPolicy event : policy.messagingEventPolicies) {
      if (event.eventName != null
          && !event.eventName.isBlank()
          && !declaredEventNames.add(event.eventName)) {
        errors.add(event.id + ": eventName must be unique across HTTP and messaging rules");
      }
    }

    Set<String> enabledMetricEvents = new HashSet<>();
    for (MessagingMetricPolicy metric : policy.messagingMetricPolicies) {
      if (metric.enabled) {
        enabledMetricEvents.add(metric.eventName);
      }
    }
    Map<String, Map<String, EventField>> eventFields = new HashMap<>();
    Set<String> headerSelectors = new HashSet<>();
    Set<String> propertySelectors = new HashSet<>();
    for (MessagingEventPolicy event : policy.messagingEventPolicies) {
      if (!event.enabled) {
        continue;
      }
      if (event.id == null || event.id.isBlank() || !policyIds.add(event.id)) {
        errors.add("messaging event policies require unique IDs");
      }
      if (event.ruleName == null || event.ruleName.isBlank() || event.ruleName.length() > 128) {
        errors.add(event.id + ": ruleName is required and limited to 128 characters");
      }
      if (!Set.of("KAFKA_PRODUCER", "KAFKA_CONSUMER", "JMS_PRODUCER", "JMS_CONSUMER")
          .contains(event.scope)) {
        errors.add(event.id + ": unsupported messaging scope " + event.scope);
      }
      if (!eventValue(event.eventName)) {
        errors.add(event.id + ": eventName must be a stable identifier");
      }
      validateMessagingStaticAttributes(event, errors);
      validateMessagingConditions(event, errors);
      if (event.maxPayloadBytes < 1024 || event.maxPayloadBytes > 262144) {
        errors.add(event.id + ": maxPayloadBytes must be between 1024 and 262144");
      }
      if (event.fields.size() > 32) {
        errors.add(event.id + ": fields exceeds its limit of 32 entries");
        continue;
      }

      Map<String, EventField> fields = new HashMap<>();
      Set<String> selectors = new HashSet<>();
      for (MessagingField field : event.fields) {
        validateAttribute(field.attribute, errors);
        if (fields.putIfAbsent(
                field.attribute,
                new EventField(field.type, field.destinations, field.valuePolicy))
            != null) {
          errors.add(event.id + ": duplicated messaging field " + field.attribute);
        }
        if (!validMessagingSelector(field.source, field.path)) {
          errors.add(
              event.id + ": invalid selector for " + field.source + ": " + field.path);
        } else if (!selectors.add(field.source + '|' + field.path)) {
          errors.add(event.id + ": duplicated messaging selector " + field.path);
        }
        if (event.scope.startsWith("KAFKA_")
            && "MESSAGE_PROPERTY".equals(field.source)) {
          errors.add(event.id + ": MESSAGE_PROPERTY is available only for JMS scopes");
        }
        if (!Set.of("STRING", "DOUBLE", "LONG", "BOOLEAN").contains(field.type)) {
          errors.add(event.id + ": unsupported messaging field type " + field.type);
        }
        if (field.destinations.isEmpty()) {
          errors.add(event.id + ": messaging fields require at least one destination");
        }
        for (String destination : field.destinations) {
          if (!Set.of("SPAN", "LOG", "METRIC").contains(destination)) {
            errors.add(event.id + ": unsupported messaging destination " + destination);
          }
        }
        if (field.destinations.contains("METRIC")) {
          validateBoundedValuePolicy(
              field.valuePolicy, event.id + "/" + field.attribute, errors);
        } else if (field.valuePolicy == null) {
          errors.add(event.id + "/" + field.attribute + ": valuePolicy must be an object");
        }
        if ("MESSAGE_HEADER".equals(field.source)) {
          headerSelectors.add(field.path);
        } else if ("MESSAGE_PROPERTY".equals(field.source)) {
          propertySelectors.add(field.path);
        }
      }
      for (MessagingCondition condition : event.conditions) {
        if ("MESSAGE_HEADER".equals(condition.source)) {
          headerSelectors.add(condition.path);
        } else if ("MESSAGE_PROPERTY".equals(condition.source)) {
          propertySelectors.add(condition.path);
        }
      }
      validateLog(event.id, event.log, errors);
      if (!messagingEventHasEffectiveOutput(event, enabledMetricEvents)) {
        errors.add(
            event.id
                + ": must define at least one effective output: SPAN, enabled log, or enabled messaging metric");
      }
      eventFields.put(event.eventName, fields);
    }
    validateSelectorLimit("MESSAGE_HEADER", headerSelectors, errors);
    validateSelectorLimit("MESSAGE_PROPERTY", propertySelectors, errors);

    for (MessagingMetricPolicy metric : policy.messagingMetricPolicies) {
      if (!metric.enabled) {
        continue;
      }
      if (metric.id == null || metric.id.isBlank() || !policyIds.add(metric.id)) {
        errors.add("messaging metric policies require unique IDs");
      }
      validateMetric(
          metric.name,
          metric.instrument,
          metric.unit,
          metric.buckets,
          names,
          normalizedNames,
          errors);
      if (!Set.of("COUNTER", "HISTOGRAM").contains(metric.instrument)) {
        errors.add(metric.name + ": messaging metrics support COUNTER or HISTOGRAM");
      }
      Map<String, EventField> fields = eventFields.get(metric.eventName);
      if (fields == null) {
        errors.add(metric.name + ": eventName does not reference an enabled messaging event");
        continue;
      }
      if (metric.valueField == null || metric.valueField.isBlank()) {
        if (!"COUNTER".equals(metric.instrument)) {
          errors.add(metric.name + ": a valueField is required for a value metric");
        }
      } else {
        EventField value = fields.get(metric.valueField);
        if (value == null || !Set.of("DOUBLE", "LONG").contains(value.type())) {
          errors.add(metric.name + ": valueField must reference a numeric messaging field");
        }
      }
      if (metric.dimensions.size() > MAX_METRIC_DIMENSIONS) {
        errors.add(
            metric.name + ": dimensions exceeds its limit of " + MAX_METRIC_DIMENSIONS);
      }
      Set<String> dimensions = new HashSet<>();
      long cardinality = 1;
      for (String dimension : metric.dimensions) {
        EventField field = fields.get(dimension);
        if (!dimensions.add(dimension)
            || field == null
            || !field.destinations().contains("METRIC")) {
          errors.add(
              metric.name
                  + ": dimension must reference a unique bounded messaging field: "
                  + dimension);
        } else {
          cardinality =
              boundedCardinalityProduct(cardinality, valueCardinality(field.valuePolicy()));
        }
      }
      if (cardinality > MAX_METRIC_CARDINALITY) {
        errors.add(
            metric.name
                + ": dimension cardinality exceeds its limit of "
                + MAX_METRIC_CARDINALITY);
      }
    }
  }

  private static void validateMessagingStaticAttributes(
      MessagingEventPolicy event, List<String> errors) {
    if (event.staticAttributes.size() > 16) {
      errors.add(event.id + ": staticAttributes exceeds its limit of 16");
      return;
    }
    Set<String> names = new HashSet<>();
    for (StaticAttribute attribute : event.staticAttributes) {
      validateAttribute(attribute.attribute, errors);
      if (!names.add(attribute.attribute)) {
        errors.add(event.id + ": duplicated static attribute " + attribute.attribute);
      }
      if (!Set.of("STRING", "DOUBLE", "LONG", "BOOLEAN").contains(attribute.type)
          || !validStaticValue(attribute.value, attribute.type)) {
        errors.add(event.id + ": invalid static attribute " + attribute.attribute);
      }
      if (attribute.destinations.isEmpty()) {
        errors.add(event.id + ": static attributes require at least one destination");
      }
      for (String destination : attribute.destinations) {
        if (!Set.of("SPAN", "LOG").contains(destination)) {
          errors.add(event.id + ": static attributes support SPAN or LOG destination");
        }
      }
    }
  }

  private static void validateMessagingConditions(
      MessagingEventPolicy event, List<String> errors) {
    if (event.conditions.isEmpty() || event.conditions.size() > 16) {
      errors.add(event.id + ": conditions requires between 1 and 16 AND conditions");
      return;
    }
    boolean hasDestination = false;
    Set<String> selectors = new HashSet<>();
    for (MessagingCondition condition : event.conditions) {
      if (!Set.of("DESTINATION", "MESSAGE_KEY", "MESSAGE_HEADER", "MESSAGE_PROPERTY", "PAYLOAD")
          .contains(condition.source)) {
        errors.add(event.id + ": unsupported messaging condition source " + condition.source);
        continue;
      }
      if (!Set.of("EQUALS", "IN").contains(condition.operator)) {
        errors.add(event.id + ": condition operator must be EQUALS or IN");
      }
      if (condition.values.isEmpty()
          || condition.values.size() > 16
          || "EQUALS".equals(condition.operator) && condition.values.size() != 1) {
        errors.add(event.id + ": EQUALS needs one value and IN supports at most 16 values");
      }
      for (String value : condition.values) {
        if (value == null || value.isBlank() || value.length() > 256) {
          errors.add(event.id + ": condition values are required and limited to 256 characters");
          break;
        }
      }
      if (!validMessagingSelector(condition.source, condition.path)) {
        errors.add(
            event.id
                + ": invalid selector for "
                + condition.source
                + ": "
                + condition.path);
      } else if (!selectors.add(condition.source + '|' + condition.path)) {
        errors.add(event.id + ": duplicated messaging condition selector " + condition.path);
      }
      if (event.scope.startsWith("KAFKA_")
          && "MESSAGE_PROPERTY".equals(condition.source)) {
        errors.add(event.id + ": MESSAGE_PROPERTY is available only for JMS scopes");
      }
      hasDestination |= "DESTINATION".equals(condition.source);
    }
    if (!hasDestination) {
      errors.add(event.id + ": a DESTINATION condition is mandatory");
    }
  }

  private static boolean validMessagingSelector(String source, String path) {
    String selector = path == null ? "" : path;
    return switch (source) {
      case "DESTINATION", "MESSAGE_KEY" -> selector.isBlank();
      case "MESSAGE_HEADER" -> HEADER_NAME.matcher(selector).matches();
      case "MESSAGE_PROPERTY" -> MESSAGE_PROPERTY_NAME.matcher(selector).matches();
      case "PAYLOAD" -> validJsonPath(selector);
      default -> false;
    };
  }

  private static boolean messagingEventHasEffectiveOutput(
      MessagingEventPolicy event, Set<String> enabledMetricEvents) {
    return (event.log != null && event.log.enabled)
        || enabledMetricEvents.contains(event.eventName)
        || event.staticAttributes.stream()
            .anyMatch(attribute -> attribute.destinations.contains("SPAN"))
        || event.fields.stream().anyMatch(field -> field.destinations.contains("SPAN"));
  }

  private static void validateDeniedBodyPaths(
      List<NamedValue> deniedPaths, List<String> errors) {
    if (deniedPaths.size() > 64) {
      errors.add("deniedBodyPaths exceeds its limit of 64");
      return;
    }
    Set<String> seen = new HashSet<>();
    for (NamedValue item : deniedPaths) {
      if (!validJsonPath(item.name) || !seen.add(normalizedJsonPath(item.name))) {
        errors.add("deniedBodyPaths contains an invalid or duplicated JSON path");
        return;
      }
    }
  }

  private static void validateJsonContentType(
      String owner, String field, String configured, List<String> errors) {
    String contentType = lower(configured);
    if (!("application/json".equals(contentType)
        || contentType.startsWith("application/") && contentType.endsWith("+json"))) {
      errors.add(owner + ": " + field + " supports application/json or application/*+json");
    }
  }

  private static void validateHttpConditions(
      BodyEventPolicy event, List<String> errors) {
    if (event.conditions.size() < 2 || event.conditions.size() > 16) {
      errors.add(event.id + ": conditions requires between 2 and 16 AND conditions");
      return;
    }
    boolean hasPath = false;
    boolean hasMethod = false;
    for (HttpCondition condition : event.conditions) {
      if (!Set.of(
              "REQUEST_PATH",
              "REQUEST_METHOD",
              "REQUEST_BODY",
              "RESPONSE_STATUS",
              "RESPONSE_BODY",
              "REQUEST_HEADER",
              "RESPONSE_HEADER",
              "REQUEST_QUERY",
              "REQUEST_PATH_PARAM")
          .contains(condition.source)) {
        errors.add(event.id + ": unsupported condition source " + condition.source);
        continue;
      }
      if (!Set.of("EQUALS", "IN").contains(condition.operator)) {
        errors.add(event.id + ": condition operator must be EQUALS or IN");
      }
      if (condition.values.isEmpty()
          || condition.values.size() > 16
          || "EQUALS".equals(condition.operator) && condition.values.size() != 1) {
        errors.add(event.id + ": EQUALS needs one value and IN supports at most 16 values");
        continue;
      }
      for (String value : condition.values) {
        if (value == null || value.isBlank() || value.length() > 128) {
          errors.add(event.id + ": condition values are required and limited to 128 characters");
          break;
        }
      }

      switch (condition.source) {
        case "REQUEST_PATH" -> {
          hasPath = true;
          if (!condition.path.isBlank()) {
            errors.add(event.id + ": REQUEST_PATH does not use a JSON path");
          }
          for (String value : condition.values) {
            if (!value.startsWith("/")
                || value.length() > 256
                || value.contains("*")
                || value.contains("?")
                || !validRequestPathTemplate(value)) {
              errors.add(
                  event.id
                      + ": request paths must be exact paths or named-segment templates");
            }
          }
        }
        case "REQUEST_METHOD" -> {
          hasMethod = true;
          if (!condition.path.isBlank()) {
            errors.add(event.id + ": REQUEST_METHOD does not use a JSON path");
          }
          for (String value : condition.values) {
            if (!Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
                .contains(value)) {
              errors.add(event.id + ": unsupported request method " + value);
            }
          }
        }
        case "RESPONSE_STATUS" -> {
          if (!condition.path.isBlank()) {
            errors.add(event.id + ": RESPONSE_STATUS does not use a JSON path");
          }
          for (String value : condition.values) {
            try {
              int status = Integer.parseInt(value);
              if (status < 100 || status > 599) {
                errors.add(event.id + ": response status must be between 100 and 599");
              }
            } catch (NumberFormatException ignored) {
              errors.add(event.id + ": response status must be numeric");
            }
          }
        }
        case "REQUEST_BODY", "RESPONSE_BODY" -> {
          if (!validJsonPath(condition.path)) {
            errors.add(event.id + ": invalid condition JSON path " + condition.path);
          }
        }
        case "REQUEST_HEADER", "RESPONSE_HEADER" -> {
          if (!HEADER_NAME.matcher(condition.path).matches()) {
            errors.add(event.id + ": invalid condition header name " + condition.path);
          }
        }
        case "REQUEST_QUERY" -> {
          if (!QUERY_NAME.matcher(condition.path).matches()) {
            errors.add(event.id + ": invalid condition query name " + condition.path);
          }
        }
        case "REQUEST_PATH_PARAM" -> {
          if (!PATH_PARAMETER_NAME.matcher(condition.path).matches()) {
            errors.add(event.id + ": invalid path parameter name " + condition.path);
          }
        }
        default -> {
          // The source set is checked before this switch.
        }
      }
    }
    if (!hasPath || !hasMethod) {
      errors.add(event.id + ": REQUEST_PATH and REQUEST_METHOD conditions are mandatory");
    }
  }

  private static void validatePathParameterSelectors(
      BodyEventPolicy event, List<String> errors) {
    Set<String> declaredParameters = new HashSet<>();
    event.conditions.stream()
        .filter(condition -> "REQUEST_PATH".equals(condition.source))
        .flatMap(condition -> condition.values.stream())
        .forEach(template -> declaredParameters.addAll(pathParameterNames(template)));

    event.conditions.stream()
        .filter(condition -> "REQUEST_PATH_PARAM".equals(condition.source))
        .map(condition -> condition.path)
        .forEach(
            selector ->
                validateDeclaredPathParameter(
                    event.id, selector, declaredParameters, errors));
    event.fields.stream()
        .filter(field -> "REQUEST_PATH_PARAM".equals(field.source))
        .map(field -> field.path)
        .forEach(
            selector ->
                validateDeclaredPathParameter(
                    event.id, selector, declaredParameters, errors));
  }

  private static void validateDeclaredPathParameter(
      String owner,
      String selector,
      Set<String> declaredParameters,
      List<String> errors) {
    if (selector != null
        && PATH_PARAMETER_NAME.matcher(selector).matches()
        && !declaredParameters.contains(selector)) {
      errors.add(
          owner
              + ": REQUEST_PATH_PARAM selector "
              + selector
              + " must appear as {"
              + selector
              + "} in a REQUEST_PATH condition");
    }
  }

  private static void validateLog(
      String owner, DynamicPolicy.LogPolicy log, List<String> errors) {
    if (log == null) {
      errors.add(owner + ": log must be an object");
      return;
    }
    if (log.enabled
        && (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(log.severity)
            || log.body == null
            || log.body.isBlank()
            || log.body.length() > 256)) {
      errors.add(owner + ": invalid log severity or body");
    }
  }

  private static void validateMetric(
      String name,
      String instrument,
      String unit,
      List<Double> buckets,
      Set<String> names,
      Set<String> normalizedNames,
      List<String> errors) {
    if (name == null || !METRIC_NAME.matcher(name).matches() || BUILT_INS.contains(name)) {
      errors.add("invalid or reserved metric name: " + name);
      return;
    }
    String normalized = prometheusName(name);
    if (!names.add(name) || !normalizedNames.add(normalized)) {
      errors.add("duplicated metric name or Prometheus collision: " + name);
    }
    if (!Set.of("HISTOGRAM", "COUNTER", "UP_DOWN_COUNTER").contains(instrument)) {
      errors.add(name + ": unsupported instrument " + instrument);
    }
    if (unit == null || unit.isBlank() || unit.length() > 32) {
      errors.add(name + ": invalid unit");
    }
    if ("HISTOGRAM".equals(instrument)) {
      double previous = -Double.MAX_VALUE;
      if (buckets.isEmpty()) {
        errors.add(name + ": histogram requires explicit buckets");
      }
      if (buckets.size() > MAX_EXPLICIT_BUCKETS) {
        errors.add(
            name + ": buckets exceeds its limit of " + MAX_EXPLICIT_BUCKETS);
      }
      for (Double bucket : buckets) {
        if (bucket == null || !Double.isFinite(bucket) || bucket <= previous) {
          errors.add(name + ": buckets must be finite and strictly increasing");
          break;
        }
        previous = bucket;
      }
    } else if (!buckets.isEmpty()) {
      errors.add(name + ": buckets are only valid for histograms");
    }
  }

  private static void validateSource(
      DynamicPolicy.ValueSource source, String owner, List<String> errors) {
    if (source == null) {
      errors.add(owner + ": value is required");
      return;
    }
    if (!Set.of("ARGUMENT", "RETURN", "DURATION", "CONSTANT").contains(source.source)) {
      errors.add(owner + ": unsupported value source " + source.source);
    }
    if ("ARGUMENT".equals(source.source) && source.argumentIndex < 0) {
      errors.add(owner + ": ARGUMENT requires argumentIndex >= 0");
    }
    String path = DynamicPolicy.normalizedObjectPath(source.path);
    if (!path.isBlank() && !PATH.matcher(path).matches()) {
      errors.add(owner + ": invalid object path " + source.path);
    }
  }

  private static void validateAttribute(String attribute, List<String> errors) {
    if (attribute == null || !ATTRIBUTE_NAME.matcher(attribute).matches()) {
      errors.add("invalid attribute: " + attribute);
    }
  }

  private static void validateBoundedValuePolicy(
      ValuePolicy policy, String owner, List<String> errors) {
    if (policy == null) {
      errors.add(owner + ": bounded value policy is required");
      return;
    }
    if (policy.fallback == null
        || policy.fallback.isBlank()
        || policy.fallback.length() > 64) {
      errors.add(owner + ": bounded value policy requires a fallback of at most 64 characters");
      return;
    }
    if ("ENUM".equals(policy.type)) {
      Set<String> unique = new HashSet<>();
      if (policy.allowed.isEmpty() || policy.allowed.size() > 32) {
        errors.add(owner + ": ENUM requires between 1 and 32 allowed values");
        return;
      }
      for (String value : policy.allowed) {
        if (value == null
            || value.isBlank()
            || value.length() > 64
            || !unique.add(value.toLowerCase(Locale.ROOT))) {
          errors.add(owner + ": ENUM values must be unique and at most 64 characters");
          return;
        }
      }
      return;
    }
    if ("RANGE".equals(policy.type)) {
      if (policy.ranges.isEmpty() || policy.ranges.size() > 20) {
        errors.add(owner + ": RANGE requires between 1 and 20 ranges");
        return;
      }
      Double previous = null;
      for (int index = 0; index < policy.ranges.size(); index++) {
        DynamicPolicy.Range range = policy.ranges.get(index);
        if (range == null) {
          errors.add(owner + ": RANGE entries must be objects");
          return;
        }
        if (range.label == null || range.label.isBlank() || range.label.length() > 64) {
          errors.add(owner + ": RANGE labels are required and limited to 64 characters");
          return;
        }
        if (range.max == null) {
          if (index != policy.ranges.size() - 1) {
            errors.add(owner + ": only the last RANGE boundary may be open");
          }
          continue;
        }
        if (!Double.isFinite(range.max) || previous != null && range.max <= previous) {
          errors.add(owner + ": RANGE boundaries must be finite and strictly increasing");
          return;
        }
        previous = range.max;
      }
      return;
    }
    if ("BOOLEAN".equals(policy.type)) {
      if (!"true".equals(policy.fallback) && !"false".equals(policy.fallback)) {
        errors.add(owner + ": BOOLEAN fallback must be true or false");
      }
      return;
    }
    errors.add(owner + ": metric labels require bounded ENUM, RANGE or BOOLEAN policy");
  }

  private static int valueCardinality(ValuePolicy policy) {
    if (policy == null || policy.type == null) {
      return MAX_METRIC_CARDINALITY + 1;
    }
    return switch (policy.type) {
      case "ENUM" -> Math.min(MAX_METRIC_CARDINALITY + 1, policy.allowed.size() + 1);
      case "RANGE" -> Math.min(MAX_METRIC_CARDINALITY + 1, policy.ranges.size() + 1);
      case "BOOLEAN" -> 2;
      default -> MAX_METRIC_CARDINALITY + 1;
    };
  }

  private static long boundedCardinalityProduct(long current, int factor) {
    if (current > MAX_METRIC_CARDINALITY || factor <= 0) {
      return MAX_METRIC_CARDINALITY + 1L;
    }
    long result = current * factor;
    return Math.min(result, MAX_METRIC_CARDINALITY + 1L);
  }

  private static void validateHeaderList(
      String section,
      List<NamedValue> values,
      int limit,
      boolean directional,
      List<String> errors) {
    if (values.size() > limit) {
      errors.add(section + ": exceeds its limit of " + limit);
      return;
    }
    Set<String> seen = new HashSet<>();
    for (NamedValue item : values) {
      if (item == null) {
        errors.add(section + ": entries must be objects");
        return;
      }
      String name = DynamicPolicy.normalizedHeaderName(item.name);
      String direction = item.direction == null ? "" : item.direction.trim().toUpperCase(Locale.ROOT);
      if (directional && !Set.of("INCOMING", "OUTGOING").contains(direction)) {
        errors.add(section + ": direction must be INCOMING or OUTGOING");
        return;
      }
      String identity = directional ? direction + ":" + name : name;
      if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]{1,128}") || !seen.add(identity)) {
        errors.add(section + ": contains an invalid or duplicated header");
        return;
      }
    }
  }

  private static Map<String, String> identities(DynamicPolicy policy) {
    Map<String, String> result = new HashMap<>();
    for (HttpMetricPolicy metric : policy.metricPolicies) {
      if (metric.enabled) {
        result.put(metric.name, identity(metric.instrument, metric.unit, metric.buckets));
      }
    }
    for (MethodPolicy method : policy.methodPolicies) {
      if (!method.enabled) {
        continue;
      }
      for (MethodMetric metric : method.metrics) {
        result.put(metric.name, identity(metric.instrument, metric.unit, metric.buckets));
      }
    }
    for (EventMetricPolicy metric : policy.eventMetricPolicies) {
      if (metric.enabled) {
        result.put(metric.name, identity(metric.instrument, metric.unit, metric.buckets));
      }
    }
    for (MessagingMetricPolicy metric : policy.messagingMetricPolicies) {
      if (metric.enabled) {
        result.put(metric.name, identity(metric.instrument, metric.unit, metric.buckets));
      }
    }
    return result;
  }

  private static String identity(String instrument, String unit, List<Double> buckets) {
    return instrument + "|" + unit + "|" + buckets;
  }

  public static String prometheusName(String name) {
    String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:]", "_");
    if (normalized.endsWith("_total")) {
      normalized = normalized.substring(0, normalized.length() - 6);
    }
    return normalized;
  }

  static boolean validJsonPath(String value) {
    return value != null && value.length() <= 256 && JSON_PATH.matcher(value.trim()).matches();
  }

  private static boolean validRequestPathTemplate(String value) {
    Set<String> names = new HashSet<>();
    for (String segment : value.split("/", -1)) {
      if (!segment.contains("{") && !segment.contains("}")) {
        continue;
      }
      if (segment.length() < 3
          || segment.charAt(0) != '{'
          || segment.charAt(segment.length() - 1) != '}') {
        return false;
      }
      String name = segment.substring(1, segment.length() - 1);
      if (!PATH_PARAMETER_NAME.matcher(name).matches() || !names.add(name)) {
        return false;
      }
    }
    return true;
  }

  private static Set<String> pathParameterNames(String template) {
    if (template == null || !validRequestPathTemplate(template)) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    for (String segment : template.split("/", -1)) {
      if (segment.length() >= 3
          && segment.charAt(0) == '{'
          && segment.charAt(segment.length() - 1) == '}') {
        names.add(segment.substring(1, segment.length() - 1));
      }
    }
    return names;
  }

  private static boolean validHttpSelector(String source, String value) {
    return switch (source) {
      case "REQUEST_BODY", "RESPONSE_BODY" -> validJsonPath(value);
      case "REQUEST_HEADER", "RESPONSE_HEADER" ->
          value != null && HEADER_NAME.matcher(value).matches();
      case "REQUEST_QUERY" -> value != null && QUERY_NAME.matcher(value).matches();
      case "REQUEST_PATH_PARAM" ->
          value != null && PATH_PARAMETER_NAME.matcher(value).matches();
      default -> false;
    };
  }

  private static String normalizedHttpSelector(String source, String value) {
    if ("REQUEST_BODY".equals(source) || "RESPONSE_BODY".equals(source)) {
      return normalizedJsonPath(value);
    }
    return value == null ? "" : value;
  }

  private static void collectHttpSelectors(
      BodyEventPolicy event,
      Set<String> requestHeaders,
      Set<String> responseHeaders,
      Set<String> requestQueries,
      Set<String> requestPathParameters) {
    for (HttpCondition condition : event.conditions) {
      collectHttpSelector(
          condition.source,
          condition.path,
          requestHeaders,
          responseHeaders,
          requestQueries,
          requestPathParameters);
    }
    for (BodyField field : event.fields) {
      collectHttpSelector(
          field.source,
          field.path,
          requestHeaders,
          responseHeaders,
          requestQueries,
          requestPathParameters);
    }
  }

  private static void collectHttpSelector(
      String source,
      String path,
      Set<String> requestHeaders,
      Set<String> responseHeaders,
      Set<String> requestQueries,
      Set<String> requestPathParameters) {
    if (!validHttpSelector(source, path)) {
      return;
    }
    switch (source) {
      case "REQUEST_HEADER" -> requestHeaders.add(path);
      case "RESPONSE_HEADER" -> responseHeaders.add(path);
      case "REQUEST_QUERY" -> requestQueries.add(path);
      case "REQUEST_PATH_PARAM" -> requestPathParameters.add(path);
      default -> {
        // Bodies do not need a separately retained selector map.
      }
    }
  }

  private static void validateSelectorLimit(
      String source, Set<String> selectors, List<String> errors) {
    if (selectors.size() > 16) {
      errors.add(source + " selectors exceed their limit of 16 unique names");
    }
  }

  static String normalizedJsonPath(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.startsWith("$")) {
      normalized = normalized.substring(1);
    }
    if (normalized.startsWith(".")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private static boolean eventValue(String value) {
    return value != null && EVENT_VALUE.matcher(value.trim()).matches();
  }

  private static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }

  private record EventField(
      String type, List<String> destinations, ValuePolicy valuePolicy) {}

  public static List<String> allowedPackages() {
    return ApplicationPackageResolver.allowedPackages();
  }

  static List<String> parseAllowedPackages(String configured) {
    return ApplicationPackageResolver.parseConfiguredPackages(configured);
  }

  private static List<String> normalizeAllowedPackages(List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return List.of();
    }
    return configured.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::trim)
        .filter(value -> JAVA_NAME.matcher(value).matches())
        .distinct()
        .toList();
  }

  private static boolean isAllowedPackage(String packagePrefix, List<String> allowedPackages) {
    return allowedPackages.stream()
        .anyMatch(
            allowed -> packagePrefix.equals(allowed) || packagePrefix.startsWith(allowed + "."));
  }
}
