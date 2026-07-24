package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicySetTest {
  @BeforeEach
  @AfterEach
  void resetPolicyState() {
    PolicyState.resetForTest();
  }

  @Test
  void acceptsLegacySinglePolicyAsTheImplicitLegacyEntry() throws Exception {
    PolicySet policySet = PolicySet.parse(policy("x-legacy"));

    assertEquals("legacy", policySet.revision());
    assertIterableEquals(List.of("legacy"), policySet.policies().keySet());
    assertEquals(1, policySet.policies().get("legacy").version());
    assertIterableEquals(
        List.of("x-legacy"), names(policySet.effectivePolicy().requestHeaders));
  }

  @Test
  void comparesSchemaSegmentsNumericallyInsteadOfAsDecimalNumbers() {
    assertTrue(PolicySet.compareSchema("1.10", "1.4") > 0);
    assertTrue(PolicySet.compareSchema("2.0", "1.99") > 0);
    assertEquals(0, PolicySet.compareSchema("1.04", "1.4"));
    assertThrows(IllegalArgumentException.class, () -> PolicySet.compareSchema("1", "1.4"));
  }

  @Test
  void snapshotDoesNotExposeACallerOwnedPolicyMap() {
    Map<String, PolicySet.PolicyDocument> source = new LinkedHashMap<>();
    source.put(
        "policy-a",
        new PolicySet.PolicyDocument("policy-a", 1, new DynamicPolicy()));
    PolicyState.Snapshot snapshot =
        new PolicyState.Snapshot("1", "revision", source, new DynamicPolicy(), "", "", "");

    source.clear();

    assertIterableEquals(List.of("policy-a"), snapshot.policies().keySet());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.policies().clear());
  }

  @Test
  void ordersPoliciesByStableIdBeforeComposingTheEffectivePolicy() throws Exception {
    PolicySet policySet =
        PolicySet.parse(
            envelope(
                "revision-7",
                entry("policy-b", 3, policy("x-policy-b")),
                entry("policy-a", 9, policy("x-policy-a"))));

    assertEquals("revision-7", policySet.revision());
    assertIterableEquals(List.of("policy-a", "policy-b"), policySet.policies().keySet());
    assertEquals(9, policySet.policies().get("policy-a").version());
    assertIterableEquals(
        List.of("x-policy-a", "x-policy-b"),
        names(policySet.effectivePolicy().requestHeaders));
  }

  @Test
  void replacesTheCompleteSetAndRemovesOnlyPoliciesAbsentFromTheNewSnapshot()
      throws Exception {
    PolicyState.applyJson(
        envelope(
            "revision-1",
            entry("policy-a", 1, policy("x-policy-a")),
            entry("policy-b", 1, policy("x-policy-b"))));

    PolicyState.Snapshot replacement =
        PolicyState.applyJson(
            envelope(
                "revision-2",
                entry("policy-c", 1, policy("x-policy-c")),
                entry("policy-b", 2, policy("x-policy-b"))));

    assertEquals("revision-2", replacement.revision());
    assertIterableEquals(List.of("policy-b", "policy-c"), replacement.policies().keySet());
    assertFalse(replacement.policies().containsKey("policy-a"));
    assertEquals(2, replacement.policies().get("policy-b").version());
    assertIterableEquals(
        List.of("x-policy-b", "x-policy-c"),
        names(PolicyState.current().requestHeaders));
  }

  @Test
  void rejectsTheWholeSetBeforeChangingTheActiveGeneration() throws Exception {
    PolicyState.Snapshot accepted =
        PolicyState.applyJson(
            envelope(
                "accepted",
                entry("policy-a", 1, policy("x-policy-a")),
                entry("policy-b", 1, policy("x-policy-b"))));
    String invalidPolicy = policy("x-invalid").replace("\"schemaVersion\"", "\"unknown\"");

    assertThrows(
        Exception.class,
        () ->
            PolicyState.applyJson(
                envelope(
                    "rejected",
                    entry("policy-a", 2, policy("x-policy-a-v2")),
                    entry("policy-invalid", 1, invalidPolicy))));

    PolicyState.Snapshot current = PolicyState.currentSnapshot();
    assertEquals(accepted.generation(), current.generation());
    assertEquals("accepted", current.revision());
    assertIterableEquals(List.of("policy-a", "policy-b"), current.policies().keySet());
    assertIterableEquals(
        List.of("x-policy-a", "x-policy-b"), names(current.effectivePolicy().requestHeaders));
  }

  @Test
  void rejectsCrossPolicyRuleAndMetricCollisions() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PolicySet.parse(
                    envelope(
                        "conflict",
                        entry("policy-a", 1, metricPolicy("metric-a")),
                        entry("policy-b", 1, metricPolicy("metric-a")))));

    assertTrue(error.getMessage().contains("effective policy set"));
    assertTrue(
        error.getMessage().contains("unique IDs")
            || error.getMessage().contains("duplicated metric name"));
  }

  @Test
  void emptyPolicySetAtomicallyClearsThePreviousEffectivePolicies() throws Exception {
    PolicyState.applyJson(
        envelope("populated", entry("policy-a", 1, policy("x-policy-a"))));

    PolicyState.Snapshot empty = PolicyState.applyJson(envelope("empty"));

    assertTrue(empty.policies().isEmpty());
    assertTrue(empty.effectivePolicy().requestHeaders.isEmpty());
    assertTrue(PolicyState.current().requestHeaders.isEmpty());
    assertEquals("", System.getProperty(PolicyState.REQUEST_HEADERS_PROPERTY));
    assertEquals("", System.getProperty(PolicyState.RESPONSE_HEADERS_PROPERTY));
    DynamicPolicy legacyMirror =
        DynamicPolicy.parse(System.getProperty(PolicyState.BODY_POLICY_PROPERTY));
    assertTrue(legacyMirror.requestHeaders.isEmpty());
    assertTrue(legacyMirror.bodyEventPolicies.isEmpty());
    assertTrue(legacyMirror.methodPolicies.isEmpty());
  }

  @Test
  void rejectsMissingVersionsDuplicateIdsAndMalformedEnvelopes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PolicySet.parse(envelope("missing-version", entry("policy-a", 0, policy("x-a")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicySet.parse(
                envelope(
                    "duplicate",
                    entry("policy-a", 1, policy("x-a")),
                    entry("policy-a", 2, policy("x-a-v2")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicySet.parse(
                """
                {"apiVersion":"o11y.dev/v1","kind":"PolicySet"}
                """));
  }

  @Test
  void rejectsOversizedAndExcessivelyNestedPolicyDocuments() {
    String oversized = " ".repeat(DynamicPolicy.MAX_POLICY_BYTES + 1);
    String deeplyNested = "{\"unknown\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}";

    IllegalArgumentException sizeError =
        assertThrows(IllegalArgumentException.class, () -> PolicySet.parse(oversized));
    assertTrue(sizeError.getMessage().contains("1048576-byte"));
    assertThrows(Exception.class, () -> PolicySet.parse(deeplyNested));
  }

  @Test
  void publishesGenerationScopedPropertiesBeforeSwitchingTheActiveGeneration()
      throws Exception {
    PolicyState.Snapshot snapshot =
        PolicyState.applyJson(
            envelope("revision-1", entry("policy-a", 1, policy("x-policy-a"))));

    String generation = snapshot.generation();
    assertEquals(generation, System.getProperty(PolicyState.ACTIVE_GENERATION_PROPERTY));
    assertEquals(
        "x-policy-a",
        System.getProperty(
            PolicyState.generationProperty(PolicyState.REQUEST_HEADERS_PROPERTY, generation)));
    assertEquals(
        snapshot.effectiveJson(),
        System.getProperty(
            PolicyState.generationProperty(PolicyState.BODY_POLICY_PROPERTY, generation)));
  }

  @Test
  void stagesAndClearsTheOutgoingHttpMetricActivationProperty() throws Exception {
    PolicyState.Snapshot snapshot =
        PolicyState.applyJson(
            envelope(
                "outgoing-metric",
                entry("policy-outgoing", 1, outgoingMetricPolicy("outgoing-metric"))));
    String generation = snapshot.generation();

    assertEquals("true", System.getProperty(PolicyState.OUTGOING_METRICS_PROPERTY));
    assertEquals(
        "true",
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.OUTGOING_METRICS_PROPERTY, generation)));

    PolicyState.applyJson(envelope("empty"));
    assertEquals("false", System.getProperty(PolicyState.OUTGOING_METRICS_PROPERTY));

    PolicyState.resetForTest();
    assertNull(System.getProperty(PolicyState.OUTGOING_METRICS_PROPERTY));
    assertNull(
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.OUTGOING_METRICS_PROPERTY, generation)));
  }

  @Test
  void keepsIdenticalHeadersInBothDirectionsAndStagesIsolatedProperties() throws Exception {
    PolicyState.Snapshot snapshot =
        PolicyState.applyJson(
            """
            {
              "schemaVersion": "1.3",
              "requestHeaders": [
                {"name": "X-Correlation-ID"},
                {"name": "x-correlation-id", "direction": "OUTGOING"}
              ],
              "responseHeaders": [
                {"name": "X-Result"},
                {"name": "x-result", "direction": "OUTGOING"}
              ]
            }
            """);

    assertEquals(2, snapshot.effectivePolicy().requestHeaders.size());
    assertIterableEquals(
        List.of("INCOMING:x-correlation-id", "OUTGOING:x-correlation-id"),
        directedNames(snapshot.effectivePolicy().requestHeaders));
    assertEquals("x-correlation-id", System.getProperty(PolicyState.REQUEST_HEADERS_PROPERTY));
    assertEquals(
        "x-correlation-id", System.getProperty(PolicyState.OUTGOING_REQUEST_HEADERS_PROPERTY));
    assertEquals("x-result", System.getProperty(PolicyState.RESPONSE_HEADERS_PROPERTY));
    assertEquals(
        "x-result", System.getProperty(PolicyState.OUTGOING_RESPONSE_HEADERS_PROPERTY));

    String generation = snapshot.generation();
    assertEquals(
        "x-correlation-id",
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.OUTGOING_REQUEST_HEADERS_PROPERTY, generation)));

    PolicyState.resetForTest();
    assertNull(System.getProperty(PolicyState.OUTGOING_REQUEST_HEADERS_PROPERTY));
    assertNull(
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.OUTGOING_REQUEST_HEADERS_PROPERTY, generation)));
  }

  @Test
  void retainsOnlyTheMostRecentSixteenGenerations() throws Exception {
    String firstGeneration = "";
    String latestGeneration = "";
    for (int index = 0; index < 20; index++) {
      PolicyState.Snapshot snapshot =
          PolicyState.applyJson(
              envelope(
                  "revision-" + index,
                  entry("policy-a", index + 1, policy("x-policy-" + index))));
      if (index == 0) {
        firstGeneration = snapshot.generation();
      }
      latestGeneration = snapshot.generation();
    }

    assertEquals(16, PolicyState.retainedGenerationCountForTest());
    assertNull(
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.REQUEST_HEADERS_PROPERTY, firstGeneration)));
    assertNotNull(
        System.getProperty(
            PolicyState.generationProperty(
                PolicyState.REQUEST_HEADERS_PROPERTY, latestGeneration)));
    assertEquals(latestGeneration, PolicyState.currentSnapshot().generation());
  }

  private static List<String> names(List<DynamicPolicy.NamedValue> values) {
    return values.stream().map(value -> value.name).toList();
  }

  private static List<String> directedNames(List<DynamicPolicy.NamedValue> values) {
    return values.stream().map(value -> value.direction + ":" + value.name).toList();
  }

  private static String policy(String requestHeader) {
    return """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [{"name": "%s"}]
        }
        """.formatted(requestHeader);
  }

  private static String metricPolicy(String ruleId) {
    return """
        {
          "schemaVersion": "1.3",
          "metricPolicies": [{
            "id": "%s",
            "enabled": true,
            "value": {
              "source": "CONSTANT",
              "argumentIndex": -1,
              "path": "",
              "constant": 1
            },
            "name": "test.policy.counter",
            "instrument": "COUNTER",
            "unit": "1",
            "description": "Policy-set conflict fixture",
            "standardAttributes": [],
            "customAttributes": [],
            "buckets": []
          }]
        }
        """.formatted(ruleId);
  }

  private static String outgoingMetricPolicy(String ruleId) {
    return metricPolicy(ruleId).replace(
        "\"enabled\": true,", "\"enabled\": true,\n    \"direction\": \"OUTGOING\",");
  }

  private static String entry(String id, int version, String policy) {
    return """
        {"id":"%s","version":%d,"policy":%s}
        """.formatted(id, version, policy.strip());
  }

  private static String envelope(String revision, String... entries) {
    return """
        {
          "apiVersion": "o11y.dev/v1",
          "kind": "PolicySet",
          "revision": "%s",
          "policies": [%s]
        }
        """.formatted(revision, String.join(",", entries));
  }
}
