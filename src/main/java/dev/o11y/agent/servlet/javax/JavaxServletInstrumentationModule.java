package dev.o11y.agent.servlet.javax;

import dev.o11y.agent.http.client.HttpClientInstrumentationHelpers;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import java.util.stream.Stream;

/** Installs the legacy {@code javax.servlet} bridge without loading Jakarta Servlet types. */
public final class JavaxServletInstrumentationModule extends InstrumentationModule {
  public JavaxServletInstrumentationModule() {
    super("o11y-dynamic-javax-servlet-policy");
  }

  @Override
  @SuppressWarnings("deprecation") // Required by the extension API pinned to Java Agent 2.28.1.
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new JavaxServletTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Stream.concat(
            HttpClientInstrumentationHelpers.common().stream(),
            Stream.of(
                "dev.o11y.agent.servlet.BoundedBodyCapture",
                "dev.o11y.agent.servlet.javax.CapturingHttpServletRequest",
                "dev.o11y.agent.servlet.javax.CapturingHttpServletResponse",
                "dev.o11y.agent.servlet.javax.CapturingServletInputStream",
                "dev.o11y.agent.servlet.javax.CapturingServletOutputStream",
                "dev.o11y.agent.servlet.javax.JavaxServletExchangeAsyncListener",
                "dev.o11y.agent.servlet.javax.JavaxServletExchangeHelper",
                "dev.o11y.agent.servlet.javax.JavaxServletExchangeHelper$State",
                "dev.o11y.agent.servlet.javax.JavaxServletExchangeHelper$State$CompletionOutcome"))
        .toList();
  }
}
