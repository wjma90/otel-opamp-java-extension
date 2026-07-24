package dev.o11y.agent.messaging.jms;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
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

/** Instruments synchronous receive methods for JMS 1.1 and JMS 2 consumers. */
public final class JmsConsumerTypeInstrumentation implements TypeInstrumentation {
  private final String message;
  private final String messageConsumer;
  private final String jmsConsumer;

  public JmsConsumerTypeInstrumentation(String namespace) {
    this.message = namespace + ".Message";
    this.messageConsumer = namespace + ".MessageConsumer";
    this.jmsConsumer = namespace + ".JMSConsumer";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(message);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(messageConsumer)).or(hasSuperType(named(jmsConsumer)));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        methodMatcher(), JmsConsumerTypeInstrumentation.class.getName() + "$ConsumerAdvice");
  }

  ElementMatcher.Junction<MethodDescription> methodMatcher() {
    return named("receive")
        .or(named("receiveNoWait"))
        .and(not(isAbstract()))
        .and(returns(named(message)));
  }

  @SuppressWarnings("unused")
  public static final class ConsumerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JmsMessagingBridge.ReceiveState enter() {
      return JmsMessagingBridge.consumerEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter JmsMessagingBridge.ReceiveState state,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object message,
        @Advice.Thrown Throwable error) {
      JmsMessagingBridge.consumerExit(state, message, error);
    }
  }
}
