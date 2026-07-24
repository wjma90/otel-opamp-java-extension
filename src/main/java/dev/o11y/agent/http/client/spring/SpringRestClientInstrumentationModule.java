package dev.o11y.agent.http.client.spring;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

/** Installs dynamic outbound policy capture in Spring's synchronous {@code RestClient}. */
public final class SpringRestClientInstrumentationModule extends InstrumentationModule {
  public SpringRestClientInstrumentationModule() {
    super("o11y-dynamic-spring-restclient-policy");
  }

  @Override
  @SuppressWarnings("deprecation") // Required by the extension API pinned to Java Agent 2.28.1.
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new SpringRestClientTypeInstrumentation(),
        new SpringRestClientBuilderTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return java.util.stream.Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            SpringRestClientBuilderTypeInstrumentation.helperClassNames().stream())
        .toList();
  }
}
