package dev.o11y.agent.messaging.jms;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
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

/**
 * Instruments classic MessageProducer plus JMSProducer Message, String and byte[] send overloads.
 */
public final class JmsProducerTypeInstrumentation implements TypeInstrumentation {
  private final String message;
  private final String messageProducer;
  private final String jmsProducer;

  public JmsProducerTypeInstrumentation(String namespace) {
    this.message = namespace + ".Message";
    this.messageProducer = namespace + ".MessageProducer";
    this.jmsProducer = namespace + ".JMSProducer";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(message);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(messageProducer)).or(hasSuperType(named(jmsProducer)));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        methodMatcher(), JmsProducerTypeInstrumentation.class.getName() + "$ProducerAdvice");
  }

  ElementMatcher.Junction<MethodDescription> methodMatcher() {
    return named("send")
        .and(not(isAbstract()))
        .and(
            takesArgument(0, named(message))
                .or(takesArgument(1, named(message)))
                .or(takesArgument(1, String.class))
                .or(takesArgument(1, byte[].class)));
  }

  @SuppressWarnings("unused")
  public static final class ProducerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MessagingExchange.State enter(
        @Advice.This Object producer, @Advice.AllArguments Object[] arguments) {
      return JmsMessagingBridge.producerEnter(producer, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter MessagingExchange.State state, @Advice.Thrown Throwable error) {
      JmsMessagingBridge.producerExit(state, error);
    }
  }
}
