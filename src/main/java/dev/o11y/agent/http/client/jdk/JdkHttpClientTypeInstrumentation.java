package dev.o11y.agent.http.client.jdk;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Instruments every concrete JDK HttpClient, including custom provider implementations. */
public final class JdkHttpClientTypeInstrumentation implements TypeInstrumentation {
  private static final String HTTP_CLIENT = "java.net.http.HttpClient";
  private static final String HTTP_REQUEST = "java.net.http.HttpRequest";
  private static final String BODY_HANDLER = "java.net.http.HttpResponse$BodyHandler";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(HTTP_CLIENT, HTTP_REQUEST, BODY_HANDLER);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(HTTP_CLIENT)).and(not(isAbstract()));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> exchange =
        named("send")
            .or(named("sendAsync"))
            .and(not(isAbstract()))
            .and(takesArguments(2).or(takesArguments(3)))
            .and(takesArgument(0, named(HTTP_REQUEST)))
            .and(takesArgument(1, named(BODY_HANDLER)));
    transformer.applyAdviceToMethod(
        exchange, JdkHttpClientTypeInstrumentation.class.getName() + "$ClientAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JdkHttpClientBridge.State enter(
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object request,
        @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object responseHandler) {
      JdkHttpClientBridge.State state = JdkHttpClientBridge.enter(request, responseHandler);
      request = state.request();
      responseHandler = state.responseHandler();
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter JdkHttpClientBridge.State state,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
        @Advice.Thrown Throwable failure) {
      JdkHttpClientBridge.exit(state, result, failure);
    }
  }
}
