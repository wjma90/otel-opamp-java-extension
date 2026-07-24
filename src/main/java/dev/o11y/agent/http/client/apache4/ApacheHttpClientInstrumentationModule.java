package dev.o11y.agent.http.client.apache4;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded OUTGOING policy capture for Apache HttpClient 4.3–4.5.x. */
public final class ApacheHttpClientInstrumentationModule extends InstrumentationModule {
  public ApacheHttpClientInstrumentationModule() {
    super("o11y-dynamic-apache-httpclient-4-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new ApacheHttpClientTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    String bridge = ApacheHttpClientBridge.class.getName();
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            Stream.of(
                bridge,
                bridge + "$State",
                bridge + "$RequestEntityHandler",
                bridge + "$ResponseEntityHandler",
                bridge + "$StreamingResponseEntityHandler",
                bridge + "$BoundedRecordingInputStream",
                bridge + "$FaultingInputStream",
                bridge + "$ReadResult"))
        .toList();
  }
}
