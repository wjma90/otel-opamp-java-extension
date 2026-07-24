package dev.o11y.agent.messaging.kafka;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Emits one consumer policy event per record returned by KafkaConsumer.poll. */
public final class KafkaConsumerTypeInstrumentation implements TypeInstrumentation {
  private static final String CONSUMER = "org.apache.kafka.clients.consumer.KafkaConsumer";
  private static final String RECORDS = "org.apache.kafka.clients.consumer.ConsumerRecords";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed(
        CONSUMER, RECORDS);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(CONSUMER);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> poll =
        named("poll").and(not(isAbstract())).and(returns(named(RECORDS)));
    transformer.applyAdviceToMethod(
        poll, KafkaConsumerTypeInstrumentation.class.getName() + "$ConsumerAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ConsumerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static KafkaMessagingBridge.PollState enter() {
      return KafkaMessagingBridge.consumerEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter KafkaMessagingBridge.PollState state,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object records,
        @Advice.Thrown Throwable error) {
      KafkaMessagingBridge.consumerExit(state, records, error);
    }
  }
}
