package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagingPolicyCompilerTest {
  @Test
  void compilesMessagingEventsIntoTheSharedBoundedRuntime() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(policySource());

    String compiled = BodyPolicyCompiler.compile(policy);

    assertTrue(compiled.startsWith("V|1\n"));
    assertTrue(compiled.contains("\nE|"));
    assertTrue(compiled.contains("\nC|"));
    assertTrue(compiled.contains("\nF|"));
    assertTrue(compiled.contains("\nI|"));
    assertEquals("KAFKA_PRODUCER", policy.messagingEventPolicies().getFirst().scope);
    assertEquals(
        "x-client-channel",
        policy.messagingEventPolicies().getFirst().fields.getFirst().path);
  }

  @Test
  void rejectsMessagingPoliciesBeforeSchemaOnePointFive() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(policySource().replace("\"1.5\"", "\"1.4\""));

    assertTrue(
        PolicyValidator.validate(policy).stream()
            .anyMatch(error -> error.contains("require schemaVersion 1.5")));
  }

  @Test
  void requiresDestinationToBoundEveryMessagingRule() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            policySource()
                .replaceFirst("\"source\": \"DESTINATION\"", "\"source\": \"MESSAGE_KEY\""));

    List<String> errors = PolicyValidator.validate(policy);

    assertTrue(errors.stream().anyMatch(error -> error.contains("DESTINATION condition")));
  }

  @Test
  void validatesTheCompleteKafkaPolicyAndBoundedMetricDimension() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(policySource());
    List<String> errors = PolicyValidator.validate(policy);

    assertTrue(errors.isEmpty(), errors.toString());
  }

  private String policySource() throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/messaging-kafka-event.json")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
