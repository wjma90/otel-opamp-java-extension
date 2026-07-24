package dev.o11y.agent.messaging.kafka;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import dev.o11y.agent.messaging.MessagingExchange;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Captures accepted Kafka sends; broker acknowledgement remains a separate async operation. */
public final class KafkaProducerTypeInstrumentation implements TypeInstrumentation {
  private static final String PRODUCER = "org.apache.kafka.clients.producer.KafkaProducer";
  private static final String RECORD = "org.apache.kafka.clients.producer.ProducerRecord";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed(
        PRODUCER, RECORD);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(PRODUCER);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> send =
        named("send").and(not(isAbstract())).and(takesArgument(0, named(RECORD)));
    transformer.applyAdviceToMethod(
        send, KafkaProducerTypeInstrumentation.class.getName() + "$ProducerAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ProducerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MessagingExchange.State enter(@Advice.Argument(0) Object record) {
      return KafkaMessagingBridge.producerEnter(record);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter MessagingExchange.State state, @Advice.Thrown Throwable error) {
      KafkaMessagingBridge.producerExit(state, error);
    }
  }
}
