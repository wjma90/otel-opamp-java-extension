package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyExamplesTest {
  @Test
  void validatesTheRequestHeaderMetricExample() throws Exception {
    DynamicPolicy policy = load("http-request-header-metric.json");

    var errors = PolicyValidator.validate(policy);
    assertTrue(errors.isEmpty(), () -> errors.toString());
    assertEquals(1, policy.metricPolicies.size());
    assertTrue(policy.methodPolicies.isEmpty());
  }

  @Test
  void validatesAndCompilesTheMethodBusinessMetricsExample() throws Exception {
    DynamicPolicy policy = load("method-business-metrics.json");

    assertTrue(
        PolicyValidator.validate(policy, List.of("dev.o11y.rates.service")).isEmpty());
    assertEquals(1, policy.methodPolicies.size());
    assertTrue(MethodPolicyCompiler.compile(policy).contains("\nI|"));
  }

  @Test
  void validatesTheRequestAndResponseBodyBusinessEventExample() throws Exception {
    DynamicPolicy policy = load("http-body-business-event.json");

    assertTrue(PolicyValidator.validate(policy).isEmpty());
    assertEquals(1, policy.bodyEventPolicies.size());
    assertEquals(3, policy.eventMetricPolicies.size());
    assertEquals(10, policy.bodyEventPolicies.getFirst().conditions.size());
    assertEquals(
        "cambistapp.currency_exchange.operations",
        policy.eventMetricPolicies.getFirst().name);
  }

  @Test
  void validatesTheCambistappPenAndUsdAmountPolicy() throws Exception {
    DynamicPolicy policy = load("cambistapp-pen-usd-source-target.json");

    var errors = PolicyValidator.validate(policy);
    assertTrue(errors.isEmpty(), () -> errors.toString());
    assertEquals(1, policy.bodyEventPolicies.size());
    assertEquals(2, policy.eventMetricPolicies.size());
    assertEquals(
        "cambistapp.currency_exchange.target.amount",
        policy.eventMetricPolicies.get(1).name);
    assertEquals(
        "cambistapp.currency_exchange.target.amount",
        policy.eventMetricPolicies.get(1).valueField);
  }

  @Test
  void validatesTheCalculatedBodyFieldExample() throws Exception {
    DynamicPolicy policy = load("http-body-calculated-total.json");

    var errors = PolicyValidator.validate(policy);
    assertTrue(errors.isEmpty(), () -> errors.toString());
    assertEquals("1.3", policy.schemaVersion);
    assertEquals(1, policy.bodyEventPolicies.getFirst().derivedFields.size());
    assertEquals(
        "transaction.total",
        policy.eventMetricPolicies.get(1).valueField);
  }

  @Test
  void validatesAndCompilesTheOutgoingRatesClientExample() throws Exception {
    DynamicPolicy policy = load("http-outgoing-rates-client.json");

    var errors = PolicyValidator.validate(policy);
    assertTrue(errors.isEmpty(), () -> errors.toString());
    assertEquals("OUTGOING", policy.bodyEventPolicies.getFirst().direction);
    assertEquals(2, policy.eventMetricPolicies.size());
    assertTrue(BodyPolicyCompiler.compile(policy).contains("\nI|"));
  }

  @Test
  void validatesAndCompilesTheQuarkusKafkaAndJmsSmokePolicy() throws Exception {
    DynamicPolicy policy = load("quarkus-messaging-event.json");

    var errors = PolicyValidator.validate(policy);
    assertTrue(errors.isEmpty(), () -> errors.toString());
    assertEquals(4, policy.messagingEventPolicies.size());
    assertEquals(4, policy.messagingMetricPolicies.size());
    assertTrue(BodyPolicyCompiler.compile(policy).contains("\nI|"));
  }

  private static DynamicPolicy load(String name) throws Exception {
    String resource = "/policies/" + name;
    try (InputStream input = PolicyExamplesTest.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Missing test policy fixture " + resource);
      }
      return DynamicPolicy.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
