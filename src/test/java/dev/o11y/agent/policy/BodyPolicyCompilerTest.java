package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BodyPolicyCompilerTest {
  @Test
  void compilesBodyRulesWithoutEmbeddingRawBusinessValues() throws Exception {
    String source;
    try (var input =
        getClass().getResourceAsStream("/policies/http-body-calculated-total.json")) {
      source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    String compiled = BodyPolicyCompiler.compile(DynamicPolicy.parse(source));

    assertTrue(compiled.startsWith("V|1\n"));
    assertTrue(compiled.contains("\nE|"));
    assertTrue(compiled.contains("\nD|"));
    assertTrue(compiled.contains("\nI|"));
    assertFalse(compiled.contains("Calculated transfer"));
  }
}
