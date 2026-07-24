package dev.o11y.agent.method;

import dev.o11y.agent.method.discovery.ApplicationPackageResolver;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

public final class DynamicMethodInstrumentationModule extends InstrumentationModule {
  public DynamicMethodInstrumentationModule() {
    super("o11y-dynamic-method-policy");
  }

  @Override
  @SuppressWarnings("deprecation") // Required by the extension API pinned to Java Agent 2.28.1.
  public boolean isIndyModule() {
    return true;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(
        new DynamicMethodTypeInstrumentation(ApplicationPackageResolver.allowedPackages()));
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return List.of(
        MethodCaptureHelper.class.getName(),
        MethodCaptureHelper.MethodRule.class.getName(),
        MethodCaptureHelper.CaptureRule.class.getName(),
        MethodCaptureHelper.MetricRule.class.getName(),
        MethodCaptureHelper.ValueSource.class.getName(),
        MethodCaptureHelper.ValuePolicy.class.getName(),
        MethodCaptureHelper.Range.class.getName(),
        MethodCaptureHelper.InstrumentHandle.class.getName());
  }
}
