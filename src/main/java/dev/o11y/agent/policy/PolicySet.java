package dev.o11y.agent.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A validated, complete policy snapshot delivered in one OpAMP remote-config file. */
public final class PolicySet {
  public static final String API_VERSION = "o11y.dev/v1";
  public static final String KIND = "PolicySet";
  private static final String LEGACY_ID = "legacy";
  private static final ObjectMapper JSON = jsonMapper();

  private final String revision;
  private final Map<String, PolicyDocument> policies;
  private final DynamicPolicy effectivePolicy;
  private final String effectiveJson;

  private PolicySet(
      String revision,
      Map<String, PolicyDocument> policies,
      DynamicPolicy effectivePolicy,
      String effectiveJson) {
    this.revision = revision;
    this.policies = Collections.unmodifiableMap(new LinkedHashMap<>(policies));
    this.effectivePolicy = effectivePolicy;
    this.effectiveJson = effectiveJson;
  }

  public static PolicySet parse(String source) throws Exception {
    DynamicPolicy.validateSourceSize(source);
    JsonNode root = JSON.readTree(source);
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("policy document must be a JSON object");
    }
    if (!root.has("apiVersion") && !root.has("kind") && !root.has("policies")) {
      DynamicPolicy legacy = DynamicPolicy.parse(source);
      validateDocument(LEGACY_ID, legacy);
      Map<String, PolicyDocument> documents =
          Map.of(LEGACY_ID, new PolicyDocument(LEGACY_ID, 1, legacy));
      DynamicPolicy effective = compose(documents);
      validateEffective(effective);
      return new PolicySet("legacy", documents, effective, DynamicPolicy.toJson(effective));
    }

    if (!root.has("policies") || !root.get("policies").isArray()) {
      throw new IllegalArgumentException("policies must be an array");
    }

    Envelope envelope = JSON.treeToValue(root, Envelope.class);
    if (!API_VERSION.equals(envelope.apiVersion)) {
      throw new IllegalArgumentException("apiVersion must be " + API_VERSION);
    }
    if (!KIND.equals(envelope.kind)) {
      throw new IllegalArgumentException("kind must be " + KIND);
    }
    if (envelope.revision != null && envelope.revision.length() > 128) {
      throw new IllegalArgumentException("revision must contain at most 128 characters");
    }
    if (envelope.policies == null) {
      throw new IllegalArgumentException("policies must be an array");
    }
    if (envelope.policies.size() > 64) {
      throw new IllegalArgumentException("a policy set supports at most 64 policies");
    }

    Map<String, PolicyDocument> sorted = new TreeMap<>();
    for (EnvelopeEntry entry : envelope.policies) {
      if (entry == null
          || entry.id == null
          || !entry.id.matches("[a-z][a-z0-9._-]{0,127}")) {
        throw new IllegalArgumentException("policy id must be a lowercase stable identifier");
      }
      if (entry.version < 1) {
        throw new IllegalArgumentException(entry.id + ": version must be a positive integer");
      }
      if (entry.policy == null || !entry.policy.isObject()) {
        throw new IllegalArgumentException(entry.id + ": policy must be a JSON object");
      }
      DynamicPolicy policy = DynamicPolicy.parse(entry.policy.toString());
      validateDocument(entry.id, policy);
      PolicyDocument previous =
          sorted.put(entry.id, new PolicyDocument(entry.id, entry.version, policy));
      if (previous != null) {
        throw new IllegalArgumentException("duplicated policy id: " + entry.id);
      }
    }

    Map<String, PolicyDocument> ordered = new LinkedHashMap<>(sorted);
    DynamicPolicy effective = compose(ordered);
    validateEffective(effective);
    return new PolicySet(
        envelope.revision == null ? "" : envelope.revision,
        ordered,
        effective,
        DynamicPolicy.toJson(effective));
  }

  private static void validateDocument(String id, DynamicPolicy policy) {
    List<String> errors = PolicyValidator.validate(policy);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(id + ": " + String.join("; ", errors));
    }
  }

  private static void validateEffective(DynamicPolicy policy) {
    List<String> errors = PolicyValidator.validate(policy);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("effective policy set: " + String.join("; ", errors));
    }
  }

  private static DynamicPolicy compose(Map<String, PolicyDocument> documents) {
    DynamicPolicy effective = new DynamicPolicy();
    effective.schemaVersion = "1.0";
    Map<String, DynamicPolicy.NamedValue> requestHeaders = new LinkedHashMap<>();
    Map<String, DynamicPolicy.NamedValue> responseHeaders = new LinkedHashMap<>();
    Map<String, DynamicPolicy.NamedValue> deniedHeaders = new LinkedHashMap<>();
    Map<String, DynamicPolicy.NamedValue> deniedBodyPaths = new LinkedHashMap<>();

    for (PolicyDocument document : documents.values()) {
      DynamicPolicy policy = document.policy();
      if (compareSchema(policy.schemaVersion, effective.schemaVersion) > 0) {
        effective.schemaVersion = policy.schemaVersion;
      }
      mergeNames(requestHeaders, policy.requestHeaders, true);
      mergeNames(responseHeaders, policy.responseHeaders, true);
      mergeNames(deniedHeaders, policy.deniedHeaders, false);
      mergeNames(deniedBodyPaths, policy.deniedBodyPaths, false);
      effective.metricPolicies.addAll(policy.metricPolicies);
      effective.methodPolicies.addAll(policy.methodPolicies);
      effective.bodyEventPolicies.addAll(policy.bodyEventPolicies);
      effective.eventMetricPolicies.addAll(policy.eventMetricPolicies);
      effective.messagingEventPolicies.addAll(policy.messagingEventPolicies);
      effective.messagingMetricPolicies.addAll(policy.messagingMetricPolicies);
    }
    effective.requestHeaders = new ArrayList<>(requestHeaders.values());
    effective.responseHeaders = new ArrayList<>(responseHeaders.values());
    effective.deniedHeaders = new ArrayList<>(deniedHeaders.values());
    effective.deniedBodyPaths = new ArrayList<>(deniedBodyPaths.values());
    return effective;
  }

  static int compareSchema(String left, String right) {
    int[] leftParts = schemaParts(left);
    int[] rightParts = schemaParts(right);
    int major = Integer.compare(leftParts[0], rightParts[0]);
    return major != 0 ? major : Integer.compare(leftParts[1], rightParts[1]);
  }

  private static int[] schemaParts(String value) {
    if (value == null || !value.matches("[0-9]+\\.[0-9]+")) {
      throw new IllegalArgumentException("schema version must use major.minor numeric format");
    }
    int separator = value.indexOf('.');
    try {
      return new int[] {
        Integer.parseInt(value.substring(0, separator)),
        Integer.parseInt(value.substring(separator + 1))
      };
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("schema version segment is outside integer range", failure);
    }
  }

  private static ObjectMapper jsonMapper() {
    ObjectMapper mapper =
        new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
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

  private static void mergeNames(
      Map<String, DynamicPolicy.NamedValue> destination,
      List<DynamicPolicy.NamedValue> values,
      boolean directional) {
    for (DynamicPolicy.NamedValue value : values) {
      String name = value.name.trim().toLowerCase(Locale.ROOT);
      String key = directional ? value.direction + ":" + name : name;
      destination.putIfAbsent(key, value);
    }
  }

  public String revision() {
    return revision;
  }

  public Map<String, PolicyDocument> policies() {
    return policies;
  }

  public DynamicPolicy effectivePolicy() {
    return effectivePolicy;
  }

  public String effectiveJson() {
    return effectiveJson;
  }

  public record PolicyDocument(String id, int version, DynamicPolicy policy) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  private static final class Envelope {
    public String apiVersion = "";
    public String kind = "";
    public String revision = "";
    public List<EnvelopeEntry> policies = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  private static final class EnvelopeEntry {
    public String id = "";
    public int version;
    public JsonNode policy;
  }
}
