package dev.o11y.agent.http.client.jdk;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded OUTGOING policy capture for JDK {@code java.net.http.HttpClient}. */
public final class JdkHttpClientInstrumentationModule extends InstrumentationModule {
  public JdkHttpClientInstrumentationModule() {
    super("o11y-dynamic-jdk-httpclient-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new JdkHttpClientTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            JdkHttpClientBridge.helperClassNames().stream())
        .distinct()
        .toList();
  }
}
