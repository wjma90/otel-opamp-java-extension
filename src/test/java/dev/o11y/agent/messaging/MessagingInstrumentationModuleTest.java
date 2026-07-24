package dev.o11y.agent.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.messaging.jms.JmsMessagingInstrumentationModule;
import dev.o11y.agent.messaging.kafka.KafkaMessagingInstrumentationModule;
import org.junit.jupiter.api.Test;

class MessagingInstrumentationModuleTest {
  @Test
  void registersProducerAndConsumerKafkaInstrumentationsAndRuntimeHelpers() {
    KafkaMessagingInstrumentationModule module = new KafkaMessagingInstrumentationModule();

    assertEquals(2, module.typeInstrumentations().size());
    assertTrue(
        module.getAdditionalHelperClassNames().contains(MessagingExchange.class.getName()));
    assertTrue(
        module.getAdditionalHelperClassNames()
            .contains(MessagingExchange.class.getName() + "$CaptureRequirements"));
    assertTrue(
        module.getAdditionalHelperClassNames()
            .contains("dev.o11y.agent.messaging.kafka.KafkaMessagingBridge$PollState"));
  }

  @Test
  void registersProducerConsumerAndListenerForBothJmsNamespaces() {
    JmsMessagingInstrumentationModule module = new JmsMessagingInstrumentationModule();

    assertEquals(6, module.typeInstrumentations().size());
    assertTrue(
        module.getAdditionalHelperClassNames()
            .contains("dev.o11y.agent.messaging.jms.JmsMessagingBridge$ReceiveState"));
  }
}
