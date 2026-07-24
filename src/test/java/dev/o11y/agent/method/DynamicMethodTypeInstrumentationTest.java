package dev.o11y.agent.method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

class DynamicMethodTypeInstrumentationTest {
  @Test
  void emptyAllowlistMatchesNoApplicationTypes() {
    var instrumentation = new DynamicMethodTypeInstrumentation(List.of());

    assertFalse(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(ExampleService.class)));
  }

  @Test
  void configuredPrefixMatchesOnlyItsPackageBoundary() {
    var instrumentation =
        new DynamicMethodTypeInstrumentation(List.of("dev.o11y.agent.method"));

    assertTrue(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(ExampleService.class)));
    assertFalse(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(String.class)));
  }

  private static final class ExampleService {}
}
