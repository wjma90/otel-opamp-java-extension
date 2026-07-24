package dev.o11y.agent.servlet;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs framework-independent Jakarta Servlet request/response capture. */
public final class DynamicServletInstrumentationModule extends InstrumentationModule {
  public DynamicServletInstrumentationModule() {
    super("o11y-dynamic-servlet-policy");
  }

  @Override
  @SuppressWarnings("deprecation") // Required by the extension API pinned to Java Agent 2.28.1.
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new DynamicServletTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            Stream.of(
                "dev.o11y.agent.servlet.BoundedBodyCapture",
                "dev.o11y.agent.servlet.CapturingHttpServletRequest",
                "dev.o11y.agent.servlet.CapturingHttpServletResponse",
                "dev.o11y.agent.servlet.CapturingServletInputStream",
                "dev.o11y.agent.servlet.CapturingServletOutputStream",
                "dev.o11y.agent.servlet.ServletExchangeAsyncListener",
                "dev.o11y.agent.servlet.ServletExchangeHelper",
                "dev.o11y.agent.servlet.ServletExchangeHelper$State",
                "dev.o11y.agent.servlet.ServletExchangeHelper$State$CompletionOutcome"))
        .toList();
  }
}
