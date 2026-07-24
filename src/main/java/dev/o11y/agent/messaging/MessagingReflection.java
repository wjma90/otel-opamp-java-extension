package dev.o11y.agent.messaging;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reflection-only access to Kafka and JMS messages from application class loaders. */
public final class MessagingReflection {
  private static final int MAX_VALUES_PER_HEADER = 4;

  private MessagingReflection() {}

  public static String kafkaDestination(Object record) {
    return record == null ? "" : text(invokeNoArgs(record, "topic"));
  }

  public static MessageSnapshot kafkaRecord(
      Object record, MessagingExchange.CaptureRequirements requirements) {
    return kafkaRecord(
        record,
        requirements.messageHeaderNames(),
        requirements.messageKeyRequired(),
        requirements.payloadRequired());
  }

  static MessageSnapshot kafkaRecord(
      Object record,
      List<String> selectedHeaderNames,
      boolean messageKeyRequired,
      boolean payloadRequired) {
    if (record == null) {
      return MessageSnapshot.EMPTY;
    }
    return new MessageSnapshot(
        kafkaDestination(record),
        messageKeyRequired ? supportedKey(invokeNoArgs(record, "key")) : null,
        kafkaHeaders(invokeNoArgs(record, "headers"), selectedHeaderNames),
        Map.of(),
        payloadRequired ? supportedPayload(invokeNoArgs(record, "value")) : null);
  }

  public static String jmsDestination(Object message, Object fallbackDestination) {
    Object destination = message == null ? null : invokeNoArgs(message, "getJMSDestination");
    if (destination == null) {
      destination = fallbackDestination;
    }
    return destinationName(destination);
  }

  public static MessageSnapshot jmsMessage(
      Object message,
      Object fallbackDestination,
      Object conveniencePayload,
      MessagingExchange.CaptureRequirements requirements) {
    return jmsMessage(
        message,
        fallbackDestination,
        conveniencePayload,
        requirements.messageHeaderNames(),
        requirements.messagePropertyNames(),
        requirements.messageKeyRequired(),
        requirements.payloadRequired());
  }

  static MessageSnapshot jmsMessage(
      Object message,
      Object fallbackDestination,
      Object conveniencePayload,
      List<String> selectedHeaderNames,
      List<String> selectedPropertyNames,
      boolean messageKeyRequired,
      boolean payloadRequired) {
    String destination = jmsDestination(message, fallbackDestination);
    if (message == null) {
      return new MessageSnapshot(
          destination,
          null,
          Map.of(),
          Map.of(),
          payloadRequired ? supportedPayload(conveniencePayload) : null);
    }
    return new MessageSnapshot(
        destination,
        messageKeyRequired ? invokeNoArgs(message, "getJMSCorrelationID") : null,
        jmsHeaders(message, selectedHeaderNames),
        jmsProperties(message, selectedPropertyNames),
        payloadRequired ? supportedPayload(invokeNoArgs(message, "getText")) : null);
  }

  public static Object jmsMessageArgument(Object[] arguments) {
    if (arguments == null) {
      return null;
    }
    for (Object argument : arguments) {
      if (argument != null && hasNoArgMethod(argument.getClass(), "getJMSDestination")) {
        return argument;
      }
    }
    return null;
  }

  public static Object jmsDestinationArgument(Object[] arguments, Object message) {
    if (arguments != null) {
      for (Object argument : arguments) {
        if (argument != null
            && argument != message
            && (hasNoArgMethod(argument.getClass(), "getQueueName")
                || hasNoArgMethod(argument.getClass(), "getTopicName"))) {
          return argument;
        }
      }
    }
    return null;
  }

  public static Object jmsConveniencePayload(Object[] arguments, Object destination) {
    if (arguments == null) {
      return null;
    }
    for (Object argument : arguments) {
      if (argument != destination && (argument instanceof String || argument instanceof byte[])) {
        return argument;
      }
    }
    return null;
  }

  private static Map<String, List<String>> kafkaHeaders(
      Object headers, List<String> selectedNames) {
    if (!(headers instanceof Iterable<?> iterable)
        || selectedNames == null
        || selectedNames.isEmpty()) {
      return Map.of();
    }
    Set<String> selected = Set.copyOf(selectedNames);
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Object header : iterable) {
      String key = text(invokeNoArgs(header, "key"));
      String normalizedKey = key.toLowerCase(Locale.ROOT);
      if (!selected.contains(normalizedKey)) {
        continue;
      }
      List<String> existing = result.get(normalizedKey);
      if (existing != null && existing.size() >= MAX_VALUES_PER_HEADER) {
        continue;
      }
      String value = utf8(invokeNoArgs(header, "value"));
      if (key.isBlank() || value == null) {
        continue;
      }
      List<String> values =
          result.computeIfAbsent(normalizedKey, ignored -> new ArrayList<>());
      values.add(value);
    }
    Map<String, List<String>> immutable = new LinkedHashMap<>();
    result.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
    return Map.copyOf(immutable);
  }

  private static Map<String, List<String>> jmsHeaders(
      Object message, List<String> selectedNames) {
    if (selectedNames == null || selectedNames.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String selectedName : selectedNames) {
      String getter = jmsHeaderGetter(selectedName);
      if (getter != null) {
        addHeader(result, selectedName, invokeNoArgs(message, getter));
      }
    }
    return Map.copyOf(result);
  }

  private static String jmsHeaderGetter(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "jmscorrelationid" -> "getJMSCorrelationID";
      case "jmsmessageid" -> "getJMSMessageID";
      case "jmstype" -> "getJMSType";
      case "jmsredelivered" -> "getJMSRedelivered";
      case "jmspriority" -> "getJMSPriority";
      case "jmsdeliverymode" -> "getJMSDeliveryMode";
      case "jmsexpiration" -> "getJMSExpiration";
      case "jmstimestamp" -> "getJMSTimestamp";
      default -> null;
    };
  }

  private static Map<String, String> jmsProperties(
      Object message, List<String> selectedNames) {
    if (selectedNames == null || selectedNames.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (String name : selectedNames) {
      Object value = invoke(message, "getObjectProperty", new Class<?>[] {String.class}, name);
      if (scalar(value)) {
        result.put(name, String.valueOf(value));
      }
    }
    return Map.copyOf(result);
  }

  private static void addHeader(Map<String, List<String>> target, String name, Object value) {
    if (scalar(value)) {
      target.put(name, List.of(String.valueOf(value)));
    }
  }

  private static boolean scalar(Object value) {
    return value instanceof String || value instanceof Number || value instanceof Boolean;
  }

  private static Object supportedPayload(Object value) {
    return value instanceof String || value instanceof byte[] ? value : null;
  }

  private static Object supportedKey(Object value) {
    if (scalar(value)) {
      return value;
    }
    return value instanceof byte[] ? utf8(value) : null;
  }

  private static String destinationName(Object destination) {
    if (destination == null) {
      return "";
    }
    Object queue = invokeNoArgs(destination, "getQueueName");
    if (queue != null) {
      return String.valueOf(queue);
    }
    Object topic = invokeNoArgs(destination, "getTopicName");
    return topic == null ? "" : String.valueOf(topic);
  }

  private static String utf8(Object value) {
    if (!(value instanceof byte[] bytes)) {
      return value == null ? null : String.valueOf(value);
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ignored) {
      return null;
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean hasNoArgMethod(Class<?> type, String name) {
    return findMethod(type, name, new Class<?>[0]) != null;
  }

  public static Object invokeNoArgs(Object target, String name) {
    return invoke(target, name, new Class<?>[0]);
  }

  private static Object invoke(
      Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
    if (target == null) {
      return null;
    }
    Method method = findMethod(target.getClass(), name, parameterTypes);
    if (method == null) {
      return null;
    }
    try {
      if (!method.canAccess(target)) {
        method.trySetAccessible();
      }
      return method.invoke(target, arguments);
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
      return null;
    }
  }

  private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
    try {
      return type.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException ignored) {
      for (Class<?> current = type; current != null; current = current.getSuperclass()) {
        try {
          return current.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException missing) {
          // Continue through the hierarchy.
        }
      }
      return null;
    }
  }

  public record MessageSnapshot(
      String destination,
      Object key,
      Map<String, List<String>> headers,
      Map<String, String> properties,
      Object payload) {
    public MessageSnapshot {
      destination = destination == null ? "" : destination;
      headers = immutableHeaders(headers);
      properties = properties == null || properties.isEmpty() ? Map.of() : Map.copyOf(properties);
      if (payload instanceof byte[] bytes) {
        payload = bytes.clone();
      }
    }

    @Override
    public Object payload() {
      return payload instanceof byte[] bytes ? bytes.clone() : payload;
    }

    @Override
    public Map<String, List<String>> headers() {
      return immutableHeaders(headers);
    }

    private static Map<String, List<String>> immutableHeaders(
        Map<String, List<String>> source) {
      if (source == null || source.isEmpty()) {
        return Map.of();
      }
      Map<String, List<String>> copy = new LinkedHashMap<>();
      source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
      return Map.copyOf(copy);
    }

    private static final MessageSnapshot EMPTY =
        new MessageSnapshot("", null, Map.of(), Map.of(), null);
  }
}
