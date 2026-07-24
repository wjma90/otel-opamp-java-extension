package dev.o11y.agent.messaging;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import dev.o11y.agent.messaging.jms.JmsMessagingBridge;
import dev.o11y.agent.messaging.kafka.KafkaMessagingBridge;
import java.util.List;
import java.util.stream.Stream;

/** Helper classes injected beside supported messaging clients. */
public final class MessagingInstrumentationHelpers {
  private MessagingInstrumentationHelpers() {}

  public static List<String> common() {
    String exchange = MessagingExchange.class.getName();
    String reflection = MessagingReflection.class.getName();
    String kafka = KafkaMessagingBridge.class.getName();
    String jms = JmsMessagingBridge.class.getName();
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            Stream.of(
                exchange,
                exchange + "$State",
                exchange + "$CaptureRequirements",
                exchange + "$OwnershipKey",
                reflection,
                reflection + "$MessageSnapshot",
                kafka,
                kafka + "$PollState",
                jms,
                jms + "$ReceiveState"))
        .distinct()
        .toList();
  }
}
