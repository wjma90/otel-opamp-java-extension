package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

class DynamicPolicyTest {
  @Test
  void rejectsNullForPrimitivePolicyFieldsInsteadOfSilentlyChangingDefaults() {
    assertThrows(
        MismatchedInputException.class,
        () -> DynamicPolicy.parse(policyWithMetricField("\"enabled\": null")));
    assertThrows(
        MismatchedInputException.class,
        () ->
            DynamicPolicy.parse(
                policyWithMetricField(
                    "\"value\": {\"source\": \"ARGUMENT\", \"argumentIndex\": null}")));
    assertThrows(
        MismatchedInputException.class,
        () ->
            DynamicPolicy.parse(
                policyWithMetricField(
                    "\"value\": {\"source\": \"CONSTANT\", \"constant\": null}")));
  }

  @Test
  void rejectsUnknownFieldsAndTrailingJsonInsteadOfPartiallyApplyingInput() {
    assertThrows(
        Exception.class,
        () -> DynamicPolicy.parse("{\"schemaVersion\":\"1.3\",\"unexpected\":true}"));
    assertThrows(
        Exception.class,
        () -> DynamicPolicy.parse("{\"schemaVersion\":\"1.3\"} {\"schemaVersion\":\"1.3\"}"));
  }

  @Test
  void normalizesNullableCollectionsAtTheParsingBoundary() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "requestHeaders": null,
              "responseHeaders": [{"name": "X-Rate-Type"}],
              "metricPolicies": null,
              "methodPolicies": null,
              "bodyEventPolicies": null,
              "eventMetricPolicies": null
            }
            """);

    assertTrue(policy.requestHeaders.isEmpty());
    assertTrue(policy.metricPolicies.isEmpty());
    assertTrue(policy.methodPolicies.isEmpty());
    assertTrue(policy.bodyEventPolicies.isEmpty());
    assertTrue(policy.eventMetricPolicies.isEmpty());
  }

  private static String policyWithMetricField(String field) {
    return """
        {
          "schemaVersion": "1.3",
          "metricPolicies": [{
            "id": "strict-primitives",
            %s
          }]
        }
        """.formatted(field);
  }
}
