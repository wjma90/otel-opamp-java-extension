package dev.o11y.agent.http.client.apache5;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import dev.o11y.agent.http.client.apache4.ApacheHttpClientTypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded policy capture for Apache HttpClient 5 classic and Simple async APIs. */
public final class Apache5HttpClientInstrumentationModule extends InstrumentationModule {
  public Apache5HttpClientInstrumentationModule() {
    super("o11y-dynamic-apache-httpclient5-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new Apache5HttpClientTypeInstrumentation(),
        new Apache5AsyncTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Stream.of(
            HttpClientInstrumentationHelpers.common().stream(),
            ApacheHttpClientTypeInstrumentation.helperClassNames().stream(),
            Apache5SimpleAsyncBridge.helperClassNames().stream())
        .flatMap(stream -> stream)
        .distinct()
        .toList();
  }
}
