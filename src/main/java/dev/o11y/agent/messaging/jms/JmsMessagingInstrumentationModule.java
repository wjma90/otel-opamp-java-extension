package dev.o11y.agent.messaging.jms;

import dev.o11y.agent.messaging.MessagingInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

/** Installs the same bounded policy behavior for legacy javax.jms and modern jakarta.jms APIs. */
public final class JmsMessagingInstrumentationModule extends InstrumentationModule {
  public JmsMessagingInstrumentationModule() {
    super("o11y-dynamic-jms-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new JmsProducerTypeInstrumentation("javax.jms"),
        new JmsConsumerTypeInstrumentation("javax.jms"),
        new JmsListenerTypeInstrumentation("javax.jms"),
        new JmsProducerTypeInstrumentation("jakarta.jms"),
        new JmsConsumerTypeInstrumentation("jakarta.jms"),
        new JmsListenerTypeInstrumentation("jakarta.jms"));
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return MessagingInstrumentationHelpers.common();
  }
}
