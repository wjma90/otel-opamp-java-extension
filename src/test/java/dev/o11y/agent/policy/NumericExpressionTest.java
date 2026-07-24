package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NumericExpressionTest {
  @Test
  void evaluatesFieldsWithPrecedenceAndParentheses() {
    NumericExpression.Compiled expression =
        NumericExpression.compile(
            "quantity * unitPrice + (shipping / 2)",
            Set.of("quantity", "unitPrice", "shipping"));

    assertEquals(
        160d,
        expression
            .evaluate(Map.of("quantity", 10d, "unitPrice", 15.5d, "shipping", 10d))
            .orElseThrow());
  }

  @Test
  void rejectsUnknownFieldsAndSkipsDivisionByZero() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NumericExpression.compile("quantity * unknown", Set.of("quantity")));

    NumericExpression.Compiled division =
        NumericExpression.compile("quantity / divisor", Set.of("quantity", "divisor"));
    assertTrue(division.evaluate(Map.of("quantity", 10d, "divisor", 0d)).isEmpty());
  }
}
