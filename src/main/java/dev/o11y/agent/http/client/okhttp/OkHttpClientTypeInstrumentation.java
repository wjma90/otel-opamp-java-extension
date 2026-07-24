package dev.o11y.agent.http.client.okhttp;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Installs the policy interceptor before an OkHttp client copies its builder state. */
public final class OkHttpClientTypeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(
        OkHttpClientBridge.clientClassName(),
        OkHttpClientBridge.builderClassName(),
        OkHttpClientBridge.interceptorClassName());
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(OkHttpClientBridge.clientClassName());
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> constructor =
        isConstructor()
            .and(takesArguments(1))
            .and(takesArgument(0, named(OkHttpClientBridge.builderClassName())));
    transformer.applyAdviceToMethod(
        constructor, OkHttpClientTypeInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Object builder) {
      OkHttpClientBridge.install(builder);
    }
  }
}
