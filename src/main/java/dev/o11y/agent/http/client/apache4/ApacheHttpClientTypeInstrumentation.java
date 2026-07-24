package dev.o11y.agent.http.client.apache4;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Instruments the execution boundary shared by Apache HttpClient 4.3–4.5.x implementations. */
public final class ApacheHttpClientTypeInstrumentation implements TypeInstrumentation {
  private static final String CLOSEABLE_HTTP_CLIENT =
      "org.apache.http.impl.client.CloseableHttpClient";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(
        CLOSEABLE_HTTP_CLIENT,
        "org.apache.http.HttpHost",
        "org.apache.http.HttpRequest",
        "org.apache.http.protocol.HttpContext");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(CLOSEABLE_HTTP_CLIENT));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> execute =
        named("doExecute")
            .and(not(isAbstract()))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext")));
    transformer.applyAdviceToMethod(
        execute, ApacheHttpClientTypeInstrumentation.class.getName() + "$ApacheAdvice");
  }

  /** Names a parent module can register as application-class-loader helpers. */
  public static List<String> helperClassNames() {
    return List.of(
        ApacheHttpClientBridge.class.getName(),
        ApacheHttpClientBridge.class.getName() + "$State",
        ApacheHttpClientBridge.class.getName() + "$RequestEntityHandler",
        ApacheHttpClientBridge.class.getName() + "$ResponseEntityHandler",
        ApacheHttpClientBridge.class.getName() + "$StreamingResponseEntityHandler",
        ApacheHttpClientBridge.class.getName() + "$BoundedRecordingInputStream",
        ApacheHttpClientBridge.class.getName() + "$FaultingInputStream",
        ApacheHttpClientBridge.class.getName() + "$ReadResult");
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
        @Advice.Thrown Throwable error) {
      ApacheHttpClientBridge.exit(state, response, error);
    }
  }
}
