package dev.o11y.agent.messaging.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.o11y.agent.messaging.MessagingExchange;
import dev.o11y.agent.policy.PolicyState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JmsMessagingBridgeTest {
  @AfterEach
  void resetPolicy() throws Exception {
    var reset = PolicyState.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    reset.invoke(null);
  }

  @Test
  void convenienceSendOwnsNestedMessageProducerCallsExactlyOnce() throws Exception {
    PolicyState.applyJson(jmsProducerPolicy());
    FakeDestination destination = new FakeDestination();
    FakeMessage delegatedMessage = new FakeMessage(destination);

    MessagingExchange.State convenience =
        JmsMessagingBridge.producerEnter(
            new Object(),
            new Object[] {destination, "{\"status\":\"APPROVED\",\"amount\":250}"});
    MessagingExchange.State messageOverload =
        JmsMessagingBridge.producerEnter(
            new Object(), new Object[] {destination, delegatedMessage});
    MessagingExchange.State providerSend =
        JmsMessagingBridge.producerEnter(
            new Object(), new Object[] {destination, delegatedMessage});

    assertEquals(0, JmsMessagingBridge.producerExit(providerSend, null));
    assertEquals(0, JmsMessagingBridge.producerExit(messageOverload, null));
    assertEquals(1, JmsMessagingBridge.producerExit(convenience, null));
  }

  @Test
  void failedOuterSendReleasesOwnershipForTheNextOperation() throws Exception {
    PolicyState.applyJson(jmsProducerPolicy());
    FakeDestination destination = new FakeDestination();

    MessagingExchange.State failed =
        JmsMessagingBridge.producerEnter(
            new Object(),
            new Object[] {destination, "{\"status\":\"APPROVED\",\"amount\":250}"});
    assertEquals(
        0,
        JmsMessagingBridge.producerExit(
            failed, new IllegalStateException("provider rejected send")));

    MessagingExchange.State next =
        JmsMessagingBridge.producerEnter(
            new Object(),
            new Object[] {destination, "{\"status\":\"APPROVED\",\"amount\":300}"});
    assertEquals(1, JmsMessagingBridge.producerExit(next, null));
  }

  private static String jmsProducerPolicy() {
    return """
        {
          "schemaVersion": "1.5",
          "messagingEventPolicies": [{
            "id": "jms-producer-approved",
            "ruleName": "Approved JMS exchange",
            "scope": "JMS_PRODUCER",
            "eventName": "jms-producer-approved",
            "conditions": [
              {
                "source": "DESTINATION",
                "operator": "EQUALS",
                "values": ["o11y.quarkus.exchange"]
              },
              {
                "source": "PAYLOAD",
                "path": "status",
                "operator": "EQUALS",
                "values": ["APPROVED"]
              }
            ],
            "fields": [{
              "source": "PAYLOAD",
              "path": "amount",
              "attribute": "test.jms.amount",
              "type": "DOUBLE",
              "destinations": ["SPAN"]
            }]
          }]
        }
        """;
  }

  private static final class FakeDestination {
    public String getQueueName() {
      return "o11y.quarkus.exchange";
    }
  }

  private record FakeMessage(FakeDestination destination) {
    public Object getJMSDestination() {
      return destination;
    }

    public String getText() {
      return "{\"status\":\"APPROVED\",\"amount\":250}";
    }
  }
}
