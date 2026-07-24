package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.messaging.MessagingExchange;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MessagingPolicyRuntimeTest {
  @AfterEach
  void clearPolicy() {
    PolicyState.resetForTest();
  }

  @Test
  void emitsMatchingKafkaEventFromHeaderAndBoundedJsonPayload() throws Exception {
    PolicyState.applyJson(policySource());
    Object record = new Object();

    MessagingExchange.State state =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "exchange.approved",
            record,
            "EX-7",
            Map.of("x-client-channel", List.of("WEB")),
            Map.of(),
            "{\"status\":\"APPROVED\",\"amount\":150}" );

    assertEquals(1, state.complete(null));
    assertEquals(0, state.complete(null));
  }

  @Test
  void derivesKafkaCaptureRequirementsOnlyFromMatchingPolicySelectors() throws Exception {
    PolicyState.applyJson(policySource());

    MessagingExchange.CaptureRequirements requirements =
        MessagingExchange.requirements("KAFKA_PRODUCER", "exchange.approved");

    assertTrue(requirements.active());
    assertEquals(List.of("x-client-channel"), requirements.messageHeaderNames());
    assertEquals(List.of(), requirements.messagePropertyNames());
    assertFalse(requirements.messageKeyRequired());
    assertTrue(requirements.payloadRequired());
  }

  @Test
  void rejectsNonMatchingDestinationOrFailedProducerOperation() throws Exception {
    PolicyState.applyJson(policySource());

    MessagingExchange.State wrongDestination =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "exchange.rejected",
            new Object(),
            null,
            Map.of(),
            Map.of(),
            "{\"status\":\"APPROVED\"}");
    MessagingExchange.State failed =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "exchange.approved",
            new Object(),
            null,
            Map.of("x-client-channel", List.of("WEB")),
            Map.of(),
            "{\"status\":\"APPROVED\",\"amount\":150}");

    assertEquals(0, wrongDestination.complete(null));
    assertEquals(0, failed.complete(new IllegalStateException("send rejected")));
  }

  @Test
  void messagingDestinationsAreMatchedExactlyAndCaseSensitively() throws Exception {
    PolicyState.applyJson(policySource());

    MessagingExchange.State differentCase =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "EXCHANGE.APPROVED",
            new Object(),
            null,
            Map.of("x-client-channel", List.of("WEB")),
            Map.of(),
            "{\"status\":\"APPROVED\",\"amount\":150}");

    assertEquals(0, differentCase.complete(null));
  }

  @Test
  void onlyOutermostDelegatingProducerCallOwnsTheMessage() throws Exception {
    PolicyState.applyJson(policySource());
    Object message = new Object();

    MessagingExchange.State outer =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "exchange.approved",
            message,
            null,
            Map.of("x-client-channel", List.of("WEB")),
            Map.of(),
            "{\"status\":\"APPROVED\",\"amount\":150}");
    MessagingExchange.State inner =
        MessagingExchange.capture(
            "KAFKA_PRODUCER",
            "exchange.approved",
            message,
            null,
            Map.of("x-client-channel", List.of("WEB")),
            Map.of(),
            "{\"status\":\"APPROVED\",\"amount\":150}");

    assertEquals(0, inner.complete(null));
    assertEquals(1, outer.complete(null));
  }

  @Test
  void emitsJmsConsumerEventFromPropertyHeaderAndTextPayload() throws Exception {
    PolicyState.applyJson(jmsPolicySource());

    MessagingExchange.CaptureRequirements requirements =
        MessagingExchange.requirements("JMS_CONSUMER", "cambistapp.exchange.observed");

    MessagingExchange.State state =
        MessagingExchange.capture(
            "JMS_CONSUMER",
            "cambistapp.exchange.observed",
            new Object(),
            "corr-7",
            Map.of("jmstype", List.of("exchange.observed")),
            Map.of("channel", "WEB"),
            "{\"status\":\"OBSERVED\",\"amount\":150}");

    assertEquals(1, state.complete(null));
    assertEquals(List.of("jmstype"), requirements.messageHeaderNames());
    assertEquals(List.of("channel"), requirements.messagePropertyNames());
    assertTrue(requirements.payloadRequired());
  }

  private String policySource() throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/messaging-kafka-event.json")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String jmsPolicySource() {
    return """
        {
          "schemaVersion": "1.5",
          "messagingEventPolicies": [{
            "id": "jms-exchange-observed",
            "ruleName": "Observed exchange message",
            "scope": "JMS_CONSUMER",
            "eventName": "jms-exchange-observed",
            "conditions": [
              {"source": "DESTINATION", "operator": "EQUALS", "values": ["cambistapp.exchange.observed"]},
              {"source": "MESSAGE_HEADER", "path": "JMSType", "operator": "EQUALS", "values": ["exchange.observed"]},
              {"source": "MESSAGE_PROPERTY", "path": "channel", "operator": "EQUALS", "values": ["WEB"]},
              {"source": "PAYLOAD", "path": "status", "operator": "EQUALS", "values": ["OBSERVED"]}
            ],
            "fields": [
              {
                "source": "MESSAGE_PROPERTY",
                "path": "channel",
                "attribute": "client.channel",
                "type": "STRING",
                "destinations": ["SPAN", "METRIC"],
                "valuePolicy": {
                  "type": "ENUM",
                  "allowed": ["WEB"],
                  "fallback": "OTHER"
                }
              },
              {
                "source": "PAYLOAD",
                "path": "amount",
                "attribute": "exchange.amount",
                "type": "DOUBLE",
                "destinations": ["SPAN"]
              }
            ]
          }],
          "messagingMetricPolicies": [{
            "id": "jms-exchange-count",
            "eventName": "jms-exchange-observed",
            "name": "cambistapp.jms.exchange.operations",
            "instrument": "COUNTER",
            "unit": "{message}",
            "dimensions": ["client.channel"]
          }]
        }
        """;
  }
}
