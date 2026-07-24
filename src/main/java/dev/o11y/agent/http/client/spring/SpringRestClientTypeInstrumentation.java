package dev.o11y.agent.http.client.spring;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Adds the policy interceptor last on every concrete Spring RestClient at construction time. */
public final class SpringRestClientTypeInstrumentation implements TypeInstrumentation {
  private static final String DEFAULT_REST_CLIENT =
      "org.springframework.web.client.DefaultRestClient";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(DEFAULT_REST_CLIENT);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(DEFAULT_REST_CLIENT);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor().and(takesArguments(15)),
        SpringRestClientTypeInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object interceptors,
        @Advice.Origin Class<?> clientType) {
      interceptors =
          SpringRestClientBridge.appendInterceptor(interceptors, clientType.getClassLoader());
    }
  }
}
