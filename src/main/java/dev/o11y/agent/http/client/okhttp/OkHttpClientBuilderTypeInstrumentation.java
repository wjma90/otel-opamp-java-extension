package dev.o11y.agent.http.client.okhttp;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Adds the logical-call policy interceptor before a client is built. */
public final class OkHttpClientBuilderTypeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(
        OkHttpClientBridge.builderClassName(), OkHttpClientBridge.interceptorClassName());
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(OkHttpClientBridge.builderClassName());
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("build").and(takesArguments(0)),
        OkHttpClientBuilderTypeInstrumentation.class.getName() + "$BuildAdvice");
  }

  /** Names a parent instrumentation module can register as application-loader helpers. */
  public static List<String> helperClassNames() {
    return OkHttpClientBridge.helperClassNames();
  }

  @SuppressWarnings("unused")
  public static final class BuildAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object builder) {
      OkHttpClientBridge.install(builder);
    }
  }
}
