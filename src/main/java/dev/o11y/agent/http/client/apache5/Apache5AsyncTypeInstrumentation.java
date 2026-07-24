package dev.o11y.agent.http.client.apache5;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Covers Apache HttpClient 5's common fully buffered Simple async facade. */
public final class Apache5AsyncTypeInstrumentation implements TypeInstrumentation {
  private static final String CLIENT =
      "org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient";
  private static final String REQUEST =
      "org.apache.hc.client5.http.async.methods.SimpleHttpRequest";
  private static final String CONTEXT = "org.apache.hc.core5.http.protocol.HttpContext";
  private static final String CALLBACK = "org.apache.hc.core5.concurrent.FutureCallback";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(CLIENT, REQUEST, CONTEXT, CALLBACK);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(CLIENT);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> execute =
        named("execute")
            .and(takesArguments(3))
            .and(takesArgument(0, named(REQUEST)))
            .and(takesArgument(1, named(CONTEXT)))
            .and(takesArgument(2, named(CALLBACK)));
    transformer.applyAdviceToMethod(
        execute, Apache5AsyncTypeInstrumentation.class.getName() + "$AsyncAdvice");
  }

  @SuppressWarnings("unused")
  public static final class AsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Apache5SimpleAsyncBridge.State enter(
        @Advice.Argument(0) Object request,
        @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object callback) {
      Apache5SimpleAsyncBridge.State state = Apache5SimpleAsyncBridge.enter(request, callback);
      callback = state.callback();
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter Apache5SimpleAsyncBridge.State state,
        @Advice.Thrown Throwable failure) {
      Apache5SimpleAsyncBridge.exit(state, failure);
    }
  }
}
