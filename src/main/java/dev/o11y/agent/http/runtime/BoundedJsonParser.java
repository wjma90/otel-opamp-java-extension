package dev.o11y.agent.http.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON parser used by injected helpers, where application JSON libraries are unavailable. */
final class BoundedJsonParser {
  private static final int MAX_DEPTH = 32;
  private static final int MAX_NODES = 4096;

  private final String source;
  private int position;
  private int nodes;

  private BoundedJsonParser(String source) {
    this.source = source;
  }

  static Object parse(String source) {
    BoundedJsonParser parser = new BoundedJsonParser(source);
    Object result = parser.value(0);
    parser.whitespace();
    if (parser.position != source.length()) {
      throw parser.error("trailing content");
    }
    return result;
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
      throw error("JSON limits exceeded");
    }
    whitespace();
    if (atEnd()) {
      throw error("missing value");
    }
    return switch (current()) {
      case '{' -> object(depth + 1);
      case '[' -> array(depth + 1);
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object(int depth) {
    position++;
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    whitespace();
    if (consume('}')) {
      return result;
    }
    while (true) {
      whitespace();
      if (atEnd() || current() != '"') {
        throw error("object key expected");
      }
      String key = string();
      whitespace();
      require(':');
      result.put(key, value(depth));
      whitespace();
      if (consume('}')) {
        return result;
      }
      require(',');
    }
  }

  private List<Object> array(int depth) {
    position++;
    ArrayList<Object> result = new ArrayList<>();
    whitespace();
    if (consume(']')) {
      return result;
    }
    while (true) {
      result.add(value(depth));
      whitespace();
      if (consume(']')) {
        return result;
      }
      require(',');
    }
  }

  private String string() {
    require('"');
    StringBuilder result = new StringBuilder();
    while (!atEnd()) {
      char value = source.charAt(position++);
      if (value == '"') {
        return result.toString();
      }
      if (value == '\\') {
        if (atEnd()) {
          throw error("unfinished escape");
        }
        char escaped = source.charAt(position++);
        switch (escaped) {
          case '"', '\\', '/' -> result.append(escaped);
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'u' -> appendUnicode(result);
          default -> throw error("invalid escape");
        }
      } else {
        if (value < 0x20) {
          throw error("control character in string");
        }
        result.append(value);
      }
    }
    throw error("unterminated string");
  }

  private void appendUnicode(StringBuilder target) {
    if (position + 4 > source.length()) {
      throw error("unfinished unicode escape");
    }
    try {
      char value = (char) Integer.parseInt(source.substring(position, position + 4), 16);
      position += 4;
      if (Character.isHighSurrogate(value)) {
        if (position + 6 > source.length()
            || source.charAt(position) != '\\'
            || source.charAt(position + 1) != 'u') {
          throw error("high surrogate without low surrogate");
        }
        char low =
            (char) Integer.parseInt(source.substring(position + 2, position + 6), 16);
        if (!Character.isLowSurrogate(low)) {
          throw error("invalid low surrogate");
        }
        target.append(value).append(low);
        position += 6;
      } else if (Character.isLowSurrogate(value)) {
        throw error("low surrogate without high surrogate");
      } else {
        target.append(value);
      }
    } catch (NumberFormatException ignored) {
      throw error("invalid unicode escape");
    }
  }

  private Object number() {
    int start = position;
    consume('-');
    if (consume('0')) {
      // A leading zero is complete; delimiters are checked below.
    } else {
      digits();
    }
    boolean decimal = false;
    if (consume('.')) {
      decimal = true;
      digits();
    }
    if (!atEnd() && (current() == 'e' || current() == 'E')) {
      decimal = true;
      position++;
      if (!atEnd() && (current() == '+' || current() == '-')) {
        position++;
      }
      digits();
    }
    if (start == position) {
      throw error("invalid value");
    }
    String raw = source.substring(start, position);
    try {
      if (!decimal) {
        return Long.parseLong(raw);
      }
      double result = Double.parseDouble(raw);
      if (!Double.isFinite(result)) {
        throw error("number must be finite");
      }
      return result;
    } catch (NumberFormatException ignored) {
      throw error("invalid number");
    }
  }

  private void digits() {
    int start = position;
    while (!atEnd() && Character.isDigit(current())) {
      position++;
    }
    if (start == position) {
      throw error("digit expected");
    }
  }

  private Object literal(String expected, Object result) {
    if (!source.startsWith(expected, position)) {
      throw error("invalid literal");
    }
    position += expected.length();
    return result;
  }

  private void require(char expected) {
    if (!consume(expected)) {
      throw error("expected " + expected);
    }
  }

  private boolean consume(char expected) {
    if (!atEnd() && current() == expected) {
      position++;
      return true;
    }
    return false;
  }

  private void whitespace() {
    while (!atEnd() && Character.isWhitespace(current())) {
      position++;
    }
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
