package dev.o11y.agent.messaging.jms.fixture;

import java.io.Serializable;
import java.util.Map;

/** Minimal namespace-neutral JMS shapes used only to verify Byte Buddy matchers. */
public final class JmsMatcherFixtures {
  private JmsMatcherFixtures() {}
}

interface Message {}

interface Destination {}

interface MessageProducer {}

final class ClassicProducer implements MessageProducer {
  public void send(Message message) {}

  public void send(Destination destination, Message message) {}

  public void send(Message message, int deliveryMode, int priority, long timeToLive) {}

  public void send(
      Destination destination,
      Message message,
      int deliveryMode,
      int priority,
      long timeToLive) {}

  public void send(Destination destination, Map<String, Object> unsupported) {}
}

interface JMSProducer {}

final class ModernProducer implements JMSProducer {
  public void send(Destination destination, Message message) {}

  public void send(Destination destination, String payload) {}

  public void send(Destination destination, byte[] payload) {}

  public void send(Destination destination, Map<String, Object> unsupported) {}

  public void send(Destination destination, Serializable unsupported) {}
}

interface MessageConsumer {}

interface JMSConsumer {}

final class SyncConsumer implements MessageConsumer, JMSConsumer {
  public Message receive() {
    return null;
  }

  public Message receive(long timeout) {
    return null;
  }

  public Message receiveNoWait() {
    return null;
  }

  public String receiveBody() {
    return null;
  }
}

interface MessageListener {}

final class AsyncListener implements MessageListener {
  public void onMessage(Message message) {}

  public void onMessage(String unsupported) {}
}
