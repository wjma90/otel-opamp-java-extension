package dev.o11y.agent.http.client;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reflection-only access to supported client APIs, keeping helpers independent of app libraries. */
public final class ReflectiveHttpAccess {
  private static final int MAX_HEADER_NAMES = 128;
  private static final int MAX_HEADER_VALUES_PER_NAME = 16;
  private static final int MAX_HEADER_NAME_CHARACTERS = 256;
  private static final int MAX_HEADER_VALUE_CHARACTERS = 4096;

  private ReflectiveHttpAccess() {}

  public static String method(Object request) {
    Object value = invokeNoArgs(request, "getMethod");
    if (value == null) {
      value = invokeNoArgs(request, "method");
    }
    if (value == null) {
      value = invokeNoArgs(invokeNoArgs(request, "getRequestLine"), "getMethod");
    }
    return value == null ? "" : String.valueOf(value).toUpperCase(Locale.ROOT);
  }

  public static String uri(Object request) {
    Object value = invokeNoArgs(request, "getURI");
    if (value == null) {
      value = invokeNoArgs(request, "getUri");
    }
    if (value == null) {
      value = invokeNoArgs(request, "url");
    }
    if (value == null) {
      value = invokeNoArgs(invokeNoArgs(request, "getRequestLine"), "getUri");
    }
    return value == null ? "" : String.valueOf(value);
  }

  public static int status(Object response) {
    Object value = invokeNoArgs(response, "getStatusCode");
    if (value != null && !(value instanceof Number)) {
      Object numeric = invokeNoArgs(value, "value");
      value = numeric == null ? invokeNoArgs(value, "getValue") : numeric;
    }
    if (value == null) {
      value = invokeNoArgs(invokeNoArgs(response, "getStatusLine"), "getStatusCode");
    }
    if (value == null) {
      value = invokeNoArgs(response, "getCode");
    }
    if (value == null) {
      value = invokeNoArgs(response, "code");
    }
    return value instanceof Number number ? number.intValue() : 0;
  }

  public static Map<String, List<String>> headers(Object message) {
    if (message == null) {
      return Map.of();
    }
    Object headers = invokeNoArgs(message, "getHeaders");
    if (headers == null) {
      headers = invokeNoArgs(message, "headers");
    }
    Map<String, List<String>> mapped = mapHeaders(headers);
    if (!mapped.isEmpty()) {
      return mapped;
    }
    Map<String, List<String>> apache = apacheHeaders(headers);
    if (!apache.isEmpty()) {
      return apache;
    }
    return apacheHeaders(invokeNoArgs(message, "getAllHeaders"));
  }

  public static Object invokeNoArgs(Object target, String method) {
    if (target == null) {
      return null;
    }
    try {
      Method candidate = target.getClass().getMethod(method);
      if (!candidate.canAccess(target)) {
        candidate.setAccessible(true);
      }
      return candidate.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return null;
    }
  }

  public static Object invoke(
      Object target, String method, Class<?> parameterType, Object argument) throws Throwable {
    if (target == null) {
      return null;
    }
    try {
      Method candidate = target.getClass().getMethod(method, parameterType);
      if (!candidate.canAccess(target)) {
        candidate.setAccessible(true);
      }
      return candidate.invoke(target, argument);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  public static Object invoke(
      Object target,
      String method,
      Class<?> firstType,
      Object first,
      Class<?> secondType,
      Object second)
      throws Throwable {
    if (target == null) {
      return null;
    }
    try {
      Method candidate = target.getClass().getMethod(method, firstType, secondType);
      if (!candidate.canAccess(target)) {
        candidate.setAccessible(true);
      }
      return candidate.invoke(target, first, second);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private static Map<String, List<String>> mapHeaders(Object headers) {
    if (headers instanceof Map<?, ?> map) {
      Map<String, List<String>> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (result.size() >= MAX_HEADER_NAMES) {
          break;
        }
        String name = boundedText(entry.getKey(), MAX_HEADER_NAME_CHARACTERS);
        if (name != null) {
          List<String> safeValues = values(entry.getValue());
          if (!safeValues.isEmpty()) {
            result.put(name, safeValues);
          }
        }
      }
      return result;
    }
    Object names = invokeNoArgs(headers, "names");
    if (!(names instanceof Collection<?> collection)) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Object name : collection) {
      if (result.size() >= MAX_HEADER_NAMES) {
        break;
      }
      String key = boundedText(name, MAX_HEADER_NAME_CHARACTERS);
      if (key == null) {
        continue;
      }
      try {
        Object value = invoke(headers, "values", String.class, key);
        List<String> safeValues = values(value);
        if (!safeValues.isEmpty()) {
          result.put(key, safeValues);
        }
      } catch (Throwable ignored) {
        // Ignore a header API that is not compatible with the detected shape.
      }
    }
    return result;
  }

  private static Map<String, List<String>> apacheHeaders(Object headers) {
    if (headers == null || !headers.getClass().isArray()) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    int length = Math.min(Array.getLength(headers), MAX_HEADER_NAMES);
    for (int index = 0; index < length; index++) {
      Object header = Array.get(headers, index);
      Object name = invokeNoArgs(header, "getName");
      Object value = invokeNoArgs(header, "getValue");
      String safeName = boundedText(name, MAX_HEADER_NAME_CHARACTERS);
      String safeValue = boundedText(value, MAX_HEADER_VALUE_CHARACTERS);
      if (safeName != null && safeValue != null) {
        List<String> collected = result.computeIfAbsent(safeName, ignored -> new ArrayList<>());
        if (collected.size() < MAX_HEADER_VALUES_PER_NAME) {
          collected.add(safeValue);
        }
      }
    }
    return result;
  }

  private static List<String> values(Object source) {
    if (source == null) {
      return List.of();
    }
    if (source instanceof Iterable<?> iterable) {
      ArrayList<String> result = new ArrayList<>();
      for (Object value : iterable) {
        if (result.size() >= MAX_HEADER_VALUES_PER_NAME) {
          break;
        }
        String safe = boundedText(value, MAX_HEADER_VALUE_CHARACTERS);
        if (safe != null) {
          result.add(safe);
        }
      }
      return result;
    }
    if (source.getClass().isArray()) {
      ArrayList<String> result = new ArrayList<>();
      int length = Math.min(Array.getLength(source), MAX_HEADER_VALUES_PER_NAME);
      for (int index = 0; index < length; index++) {
        Object value = Array.get(source, index);
        String safe = boundedText(value, MAX_HEADER_VALUE_CHARACTERS);
        if (safe != null) {
          result.add(safe);
        }
      }
      return result;
    }
    String safe = boundedText(source, MAX_HEADER_VALUE_CHARACTERS);
    return safe == null ? List.of() : List.of(safe);
  }

  private static String boundedText(Object value, int maximumCharacters) {
    if (!(value instanceof String text)) {
      return null;
    }
    return text.substring(0, Math.min(text.length(), maximumCharacters));
  }
}
