package dev.o11y.agent.messaging.kafka;

import dev.o11y.agent.messaging.MessagingInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

/** Installs dynamic messaging policies for Kafka producer and consumer APIs. */
public final class KafkaMessagingInstrumentationModule extends InstrumentationModule {
  public KafkaMessagingInstrumentationModule() {
    super("o11y-dynamic-kafka-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new KafkaProducerTypeInstrumentation(), new KafkaConsumerTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return MessagingInstrumentationHelpers.common();
  }
}
