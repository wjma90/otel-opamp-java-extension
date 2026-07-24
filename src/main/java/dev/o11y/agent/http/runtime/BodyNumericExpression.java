package dev.o11y.agent.http.runtime;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Dependency-free arithmetic expression used by HTTP-derived telemetry fields. */
final class BodyNumericExpression {
  private static final int MAX_LENGTH = 256;
  private static final int MAX_NODES = 64;

  private BodyNumericExpression() {}

  static Compiled compile(String source, Set<String> variables) {
    if (source == null || source.isBlank() || source.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("invalid expression length");
    }
    Parser parser = new Parser(source, variables);
    Node root = parser.expression();
    parser.whitespace();
    if (!parser.end()) {
      throw parser.error("unexpected token");
    }
    return values -> {
      try {
        double result = root.evaluate(values);
        return Double.isFinite(result) ? OptionalDouble.of(result) : OptionalDouble.empty();
      } catch (ArithmeticException ignored) {
        return OptionalDouble.empty();
      }
    };
  }

  @FunctionalInterface
  interface Compiled {
    OptionalDouble evaluate(Map<String, Double> values);
  }

  @FunctionalInterface
  private interface Node {
    double evaluate(Map<String, Double> values);
  }

  private static final class Parser {
    private final String source;
    private final Set<String> variables;
    private int position;
    private int nodes;

    private Parser(String source, Set<String> variables) {
      this.source = source;
      this.variables = Set.copyOf(variables);
    }

    private Node expression() {
      Node left = term();
      while (true) {
        whitespace();
        if (consume('+')) {
          Node previous = left;
          Node right = term();
          left = node(values -> previous.evaluate(values) + right.evaluate(values));
        } else if (consume('-')) {
          Node previous = left;
          Node right = term();
          left = node(values -> previous.evaluate(values) - right.evaluate(values));
        } else {
          return left;
        }
      }
    }

    private Node term() {
      Node left = unary();
      while (true) {
        whitespace();
        if (consume('*')) {
          Node previous = left;
          Node right = unary();
          left = node(values -> previous.evaluate(values) * right.evaluate(values));
        } else if (consume('/')) {
          Node previous = left;
          Node right = unary();
          left =
              node(
                  values -> {
                    double divisor = right.evaluate(values);
                    if (divisor == 0d) {
                      throw new ArithmeticException("division by zero");
                    }
                    return previous.evaluate(values) / divisor;
                  });
        } else {
          return left;
        }
      }
    }

    private Node unary() {
      whitespace();
      if (consume('+')) {
        return unary();
      }
      if (consume('-')) {
        Node value = unary();
        return node(values -> -value.evaluate(values));
      }
      return primary();
    }

    private Node primary() {
      whitespace();
      if (consume('(')) {
        Node nested = expression();
        whitespace();
        if (!consume(')')) {
          throw error("missing parenthesis");
        }
        return nested;
      }
      if (!end() && (Character.isDigit(current()) || current() == '.')) {
        double value = numericConstant();
        return node(ignored -> value);
      }
      String variable = identifier();
      if (!variables.contains(variable)) {
        throw error("unknown numeric field");
      }
      return node(
          values -> {
            Double value = values.get(variable);
            if (value == null || !Double.isFinite(value)) {
              throw new ArithmeticException("missing field");
            }
            return value;
          });
    }

    private double numericConstant() {
      int start = position;
      boolean exponent = false;
      while (!end()) {
        char value = current();
        if (Character.isDigit(value) || value == '.') {
          position++;
          continue;
        }
        if ((value == 'e' || value == 'E') && !exponent) {
          exponent = true;
          position++;
          if (!end() && (current() == '+' || current() == '-')) {
            position++;
          }
          continue;
        }
        break;
      }
      try {
        double result = Double.parseDouble(source.substring(start, position));
        if (!Double.isFinite(result)) {
          throw error("non-finite constant");
        }
        return result;
      } catch (NumberFormatException ignored) {
        throw error("invalid constant");
      }
    }

    private String identifier() {
      if (end() || !(Character.isLetter(current()) || current() == '_')) {
        throw error("field expected");
      }
      int start = position++;
      while (!end()
          && (Character.isLetterOrDigit(current()) || current() == '_' || current() == '.')) {
        position++;
      }
      String result = source.substring(start, position);
      if (result.endsWith(".") || result.contains("..")) {
        throw error("invalid field reference");
      }
      return result;
    }

    private Node node(Node result) {
      if (++nodes > MAX_NODES) {
        throw error("expression is too complex");
      }
      return result;
    }

    private void whitespace() {
      while (!end() && Character.isWhitespace(current())) {
        position++;
      }
    }

    private boolean consume(char expected) {
      if (!end() && current() == expected) {
        position++;
        return true;
      }
      return false;
    }

    private boolean end() {
      return position == source.length();
    }

    private char current() {
      return source.charAt(position);
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at position " + position);
    }
  }
}
