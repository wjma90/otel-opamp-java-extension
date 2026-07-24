package dev.o11y.agent.http.client.webclient;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded OUTGOING policy capture for Spring WebClient over Reactor Netty. */
public final class SpringWebClientInstrumentationModule extends InstrumentationModule {
  public SpringWebClientInstrumentationModule() {
    super("o11y-dynamic-spring-webclient-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new SpringWebClientBuilderTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    // Do not load SpringWebClientBridge in the agent class loader: Spring WebFlux is intentionally
    // provided by the application. The names are injected only after the type matcher confirms
    // that WebClient is present in that application's class loader.
    String bridge = "dev.o11y.agent.http.client.webclient.SpringWebClientBridge";
    List<String> webClientHelpers =
        List.of(
            bridge,
            bridge + "$PolicyFilter",
            bridge + "$State",
            bridge + "$CapturingRequest",
            bridge + "$BoundedResponse",
            bridge + "$WebClientRequest",
            bridge + "$WebClientResponse",
            bridge + "$WebClientAttributesGetter",
            bridge + "$RequestHeadersSetter");
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            webClientHelpers.stream())
        .distinct()
        .toList();
  }
}
