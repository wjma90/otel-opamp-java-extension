package dev.o11y.quarkus.messaging;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import java.time.Duration;

/** Quarkus Artemis JMS round trip through the standard Jakarta JMS producer and consumer APIs. */
@ApplicationScoped
@IfBuildProperty(name = "o11y.messaging.enabled", stringValue = "true")
public final class JmsExchangeFlow {
  static final String QUEUE = "o11y.quarkus.exchange";
  private static final long RECEIVE_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();

  @Inject ConnectionFactory connectionFactory;

  String roundTrip(String payload) throws JMSException {
    try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
      Queue queue = context.createQueue(QUEUE);
      try (JMSConsumer consumer = context.createConsumer(queue)) {
        context
            .createProducer()
            .setJMSType("exchange.approved")
            .setProperty("channel", "WEB")
            .send(queue, payload);

        Message incoming = consumer.receive(RECEIVE_TIMEOUT_MILLIS);
        if (incoming == null) {
          throw new IllegalStateException("Artemis did not return the exchange message");
        }
        return incoming.getBody(String.class);
      }
    }
  }
}
