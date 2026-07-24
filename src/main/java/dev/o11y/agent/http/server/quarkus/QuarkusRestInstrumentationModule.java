package dev.o11y.agent.http.server.quarkus;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs bounded incoming HTTP policy capture for Quarkus REST on Vert.x in JVM mode. */
public final class QuarkusRestInstrumentationModule extends InstrumentationModule {
  public QuarkusRestInstrumentationModule() {
    super("o11y-dynamic-quarkus-rest-policy");
  }

  @Override
  @SuppressWarnings("deprecation")
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new QuarkusRestTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    String helper = QuarkusRestExchangeHelper.class.getName();
    List<String> quarkusHelpers =
        List.of(
            helper,
            helper + "$ReadCallbackHandler",
            helper + "$State",
            "dev.o11y.agent.http.HttpServerCompletionBridge",
            QuarkusCapturingInputStream.class.getName(),
            QuarkusCapturingOutputStream.class.getName(),
            "dev.o11y.agent.servlet.BoundedBodyCapture");
    return Stream.concat(HttpClientInstrumentationHelpers.common().stream(), quarkusHelpers.stream())
        .distinct()
        .toList();
  }
}
