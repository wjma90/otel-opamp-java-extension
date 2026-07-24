package dev.o11y.quarkus.messaging;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

/** SmallRye Reactive Messaging flow that must traverse the underlying Kafka client hooks. */
@ApplicationScoped
@IfBuildProperty(name = "o11y.messaging.enabled", stringValue = "true")
public final class KafkaExchangeFlow {
  private final AtomicReference<String> lastConsumed = new AtomicReference<>();

  @Inject
  @Channel("o11y-kafka-out")
  Emitter<String> emitter;

  CompletionStage<Void> publish(String payload) {
    return emitter.send(payload);
  }

  @Incoming("o11y-kafka-in")
  CompletionStage<Void> consume(Message<String> message) {
    lastConsumed.set(message.getPayload());
    return message.ack();
  }

  String lastConsumed() {
    return lastConsumed.get();
  }
}
