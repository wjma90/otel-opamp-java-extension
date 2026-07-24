package dev.o11y.agent.policy;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Compiles a deliberately small arithmetic language for derived telemetry fields. */
final class NumericExpression {
  private static final int MAX_LENGTH = 256;
  private static final int MAX_NODES = 64;

  private NumericExpression() {}

  static Compiled compile(String source, Set<String> allowedVariables) {
    if (source == null || source.isBlank() || source.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("expression must contain between 1 and 256 characters");
    }
    Parser parser = new Parser(source, allowedVariables);
    Node root = parser.parseExpression();
    parser.skipWhitespace();
    if (!parser.atEnd()) {
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
    private final Set<String> allowedVariables;
    private int position;
    private int nodes;

    private Parser(String source, Set<String> allowedVariables) {
      this.source = source;
      this.allowedVariables = Set.copyOf(allowedVariables);
    }

    private Node parseExpression() {
      Node left = parseTerm();
      while (true) {
        skipWhitespace();
        if (consume('+')) {
          Node right = parseTerm();
          Node previous = left;
          left = node(values -> previous.evaluate(values) + right.evaluate(values));
        } else if (consume('-')) {
          Node right = parseTerm();
          Node previous = left;
          left = node(values -> previous.evaluate(values) - right.evaluate(values));
        } else {
          return left;
        }
      }
    }

    private Node parseTerm() {
      Node left = parseUnary();
      while (true) {
        skipWhitespace();
        if (consume('*')) {
          Node right = parseUnary();
          Node previous = left;
          left = node(values -> previous.evaluate(values) * right.evaluate(values));
        } else if (consume('/')) {
          Node right = parseUnary();
          Node previous = left;
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

    private Node parseUnary() {
      skipWhitespace();
      if (consume('+')) {
        return parseUnary();
      }
      if (consume('-')) {
        Node value = parseUnary();
        return node(values -> -value.evaluate(values));
      }
      return parsePrimary();
    }

    private Node parsePrimary() {
      skipWhitespace();
      if (consume('(')) {
        Node nested = parseExpression();
        skipWhitespace();
        if (!consume(')')) {
          throw error("missing closing parenthesis");
        }
        return nested;
      }
      if (!atEnd() && (Character.isDigit(current()) || current() == '.')) {
        double value = parseNumber();
        return node(ignored -> value);
      }
      String variable = parseIdentifier();
      if (!allowedVariables.contains(variable)) {
        throw error("unknown or non-numeric field " + variable);
      }
      return node(
          values -> {
            Double value = values.get(variable);
            if (value == null || !Double.isFinite(value)) {
              throw new ArithmeticException("missing field " + variable);
            }
            return value;
          });
    }

    private double parseNumber() {
      int start = position;
      boolean exponent = false;
      while (!atEnd()) {
        char current = current();
        if (Character.isDigit(current) || current == '.') {
          position++;
          continue;
        }
        if ((current == 'e' || current == 'E') && !exponent) {
          exponent = true;
          position++;
          if (!atEnd() && (current() == '+' || current() == '-')) {
            position++;
          }
          continue;
        }
        break;
      }
      try {
        double value = Double.parseDouble(source.substring(start, position));
        if (!Double.isFinite(value)) {
          throw error("numeric constant must be finite");
        }
        return value;
      } catch (NumberFormatException ignored) {
        throw error("invalid numeric constant");
      }
    }

    private String parseIdentifier() {
      if (atEnd() || !(Character.isLetter(current()) || current() == '_')) {
        throw error("expected a number, field or parenthesis");
      }
      int start = position++;
      while (!atEnd()) {
        char current = current();
        if (Character.isLetterOrDigit(current) || current == '_' || current == '.') {
          position++;
        } else {
          break;
        }
      }
      String value = source.substring(start, position);
      if (value.endsWith(".") || value.contains("..")) {
        throw error("invalid field reference " + value);
      }
      return value;
    }

    private Node node(Node value) {
      nodes++;
      if (nodes > MAX_NODES) {
        throw error("expression exceeds 64 operations and values");
      }
      return value;
    }

    private void skipWhitespace() {
      while (!atEnd() && Character.isWhitespace(current())) {
        position++;
      }
    }

    private boolean consume(char expected) {
      if (!atEnd() && current() == expected) {
        position++;
        return true;
      }
      return false;
    }

    private boolean atEnd() {
      return position >= source.length();
    }

    private char current() {
      return source.charAt(position);
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at position " + position);
    }
  }
}
