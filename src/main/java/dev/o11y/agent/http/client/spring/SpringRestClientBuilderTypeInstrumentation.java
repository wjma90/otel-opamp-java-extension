package dev.o11y.agent.http.client.spring;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Installs the policy interceptor last, immediately before a builder materializes its client. */
public final class SpringRestClientBuilderTypeInstrumentation implements TypeInstrumentation {
  private static final String BUILDER = "org.springframework.web.client.DefaultRestClientBuilder";
  private static final String BUILDER_API = "org.springframework.web.client.RestClient$Builder";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(BUILDER_API, BUILDER);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(BUILDER).or(hasSuperType(named(BUILDER_API)).and(not(isInterface())));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("build").and(takesArguments(0)),
        SpringRestClientBuilderTypeInstrumentation.class.getName() + "$BuildAdvice");
  }

  /** Names a parent instrumentation module can register as application-loader helpers. */
  public static List<String> helperClassNames() {
    return List.of(
        SpringRestClientBridge.class.getName(),
        SpringRestClientBridge.class.getName() + "$RequestInterceptorHandler",
        SpringRestClientBridge.class.getName() + "$ResponseHandler",
        SpringRestClientBridge.class.getName() + "$BoundedRecordingInputStream",
        SpringRestClientBridge.class.getName() + "$FaultingInputStream",
        SpringRestClientBridge.class.getName() + "$SpringRequest",
        SpringRestClientBridge.class.getName() + "$SpringResponse",
        SpringRestClientBridge.class.getName() + "$CaptureResult",
        SpringRestClientBridge.class.getName() + "$SpringHttpAttributesGetter",
        SpringRestClientBridge.class.getName() + "$RequestHeadersSetter");
  }

  @SuppressWarnings("unused")
  public static final class BuildAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object builder) {
      SpringRestClientBridge.install(builder);
    }
  }
}
