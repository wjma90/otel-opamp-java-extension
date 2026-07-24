package dev.o11y.agent.http.client.webclient;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Adds the policy filter immediately before a WebClient is built. */
public final class SpringWebClientBuilderTypeInstrumentation implements TypeInstrumentation {
  private static final String BUILDER =
      "org.springframework.web.reactive.function.client.DefaultWebClientBuilder";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(
        BUILDER,
        "org.springframework.web.reactive.function.client.ExchangeFilterFunction",
        "reactor.core.publisher.Flux");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(BUILDER);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("build").and(takesArguments(0)),
        SpringWebClientBuilderTypeInstrumentation.class.getName() + "$BuildAdvice");
  }

  @SuppressWarnings("unused")
  public static final class BuildAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object builder) {
      SpringWebClientBridge.install(builder);
    }
  }
}
