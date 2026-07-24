package dev.o11y.agent.http.client.spring;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Deliberately neutral factory instrumentation.
 *
 * <p>Installing from {@code RestClient.builder()} would put the policy interceptor ahead of every
 * interceptor subsequently registered by the application. Installation therefore belongs only to
 * the builder's {@code build()} boundary and the concrete RestClient constructor fallback.
 */
public final class SpringRestClientFactoryTypeInstrumentation implements TypeInstrumentation {
  private static final String REST_CLIENT = "org.springframework.web.client.RestClient";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(REST_CLIENT);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(REST_CLIENT);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // Intentionally empty. Kept as a compatibility type for already-built extension metadata.
  }
}
