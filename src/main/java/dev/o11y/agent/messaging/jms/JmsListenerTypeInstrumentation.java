package dev.o11y.agent.messaging.jms;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import dev.o11y.agent.messaging.MessagingExchange;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Emits a consumer event only after an asynchronous MessageListener completes successfully. */
public final class JmsListenerTypeInstrumentation implements TypeInstrumentation {
  private final String message;
  private final String listener;

  public JmsListenerTypeInstrumentation(String namespace) {
    this.message = namespace + ".Message";
    this.listener = namespace + ".MessageListener";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(message, listener);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(listener));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        methodMatcher(), JmsListenerTypeInstrumentation.class.getName() + "$ListenerAdvice");
  }

  ElementMatcher.Junction<MethodDescription> methodMatcher() {
    return named("onMessage")
        .and(not(isAbstract()))
        .and(takesArguments(1))
        .and(takesArgument(0, named(message)));
  }

  @SuppressWarnings("unused")
  public static final class ListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MessagingExchange.State enter(@Advice.Argument(0) Object message) {
      return JmsMessagingBridge.listenerEnter(message);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter MessagingExchange.State state, @Advice.Thrown Throwable error) {
      JmsMessagingBridge.listenerExit(state, error);
    }
  }
}
