package dev.o11y.agent.http.client.apache5;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import dev.o11y.agent.http.client.apache4.ApacheHttpClientBridge;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Applies the namespace-neutral classic bridge to Apache HttpClient 5.x. */
public final class Apache5HttpClientTypeInstrumentation implements TypeInstrumentation {
  private static final String CLIENT =
      "org.apache.hc.client5.http.impl.classic.CloseableHttpClient";
  private static final String HOST = "org.apache.hc.core5.http.HttpHost";
  private static final String REQUEST = "org.apache.hc.core5.http.ClassicHttpRequest";
  private static final String CONTEXT = "org.apache.hc.core5.http.protocol.HttpContext";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(CLIENT, HOST, REQUEST, CONTEXT);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(CLIENT));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> execute =
        named("doExecute")
            .and(not(isAbstract()))
            .and(takesArguments(3))
            .and(takesArgument(0, named(HOST)))
            .and(takesArgument(1, named(REQUEST)))
            .and(takesArgument(2, named(CONTEXT)));
    transformer.applyAdviceToMethod(
        execute, Apache5HttpClientTypeInstrumentation.class.getName() + "$ApacheAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ApacheAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ApacheHttpClientBridge.State enter(
        @Advice.Argument(0) Object target, @Advice.Argument(1) Object request) {
      return ApacheHttpClientBridge.enter(target, request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter ApacheHttpClientBridge.State state,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object response,
        @Advice.Thrown Throwable failure) {
      ApacheHttpClientBridge.exit(state, response, failure);
    }
  }
}
