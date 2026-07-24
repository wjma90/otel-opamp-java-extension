package dev.o11y.agent.http.client.okhttp;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded OUTGOING policy capture for application OkHttp 3.4+ clients. */
public final class OkHttpClientInstrumentationModule extends InstrumentationModule {
  public OkHttpClientInstrumentationModule() {
    super("o11y-dynamic-okhttp-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new OkHttpClientTypeInstrumentation(),
        new OkHttpClientBuilderTypeInstrumentation(),
        new OkHttpRequestBodyTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            OkHttpClientBridge.helperClassNames().stream())
        .distinct()
        .toList();
  }
}
