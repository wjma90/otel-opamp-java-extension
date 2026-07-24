package dev.o11y.agent.policy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles HTTP body rules into a dependency-free format consumed from the application class
 * loader.
 *
 * <p>Java Agent helper classes are injected into the servlet container class loader. Passing a
 * compact, generation-scoped snapshot through system properties keeps those helpers independent
 * from Jackson and from every application framework.
 */
final class BodyPolicyCompiler {
  private static final String FORMAT_VERSION = "1";
  private static final String LIST_SEPARATOR = "\u001f";

  private BodyPolicyCompiler() {}

  static String compile(DynamicPolicy policy) {
    StringBuilder result = new StringBuilder("V|").append(FORMAT_VERSION).append('\n');
    for (DynamicPolicy.BodyEventPolicy event : policy.bodyEventPolicies) {
      if (!event.enabled) {
        continue;
      }
      result
          .append("E|")
          .append(encoded(event.id))
          .append('|')
          .append(encoded(event.direction))
          .append('|')
          .append(encoded(event.requestContentType))
          .append('|')
          .append(encoded(event.responseContentType))
          .append('|')
          .append(event.maxBodyBytes)
          .append('|')
          .append(encoded(event.eventName))
          .append('|')
          .append(event.log.enabled)
          .append('|')
          .append(encoded(event.log.severity))
          .append('|')
          .append(encoded(event.log.body))
          .append('\n');
      for (DynamicPolicy.HttpCondition condition : event.conditions) {
        result
            .append("C|")
            .append(encoded(event.id))
            .append('|')
            .append(condition.source)
            .append('|')
            .append(encoded(condition.path))
            .append('|')
            .append(condition.operator)
            .append('|')
            .append(encodedList(condition.values))
            .append('\n');
      }
      for (DynamicPolicy.StaticAttribute attribute : event.staticAttributes) {
        result
            .append("S|")
            .append(encoded(event.id))
            .append('|')
            .append(encoded(attribute.attribute))
            .append('|')
            .append(encoded(attribute.value))
            .append('|')
            .append(attribute.type)
            .append('|')
            .append(String.join(",", attribute.destinations))
            .append('\n');
      }
      for (DynamicPolicy.BodyField field : event.fields) {
        result
            .append("F|")
            .append(encoded(event.id))
            .append('|')
            .append(field.source)
            .append('|')
            .append(encoded(field.path))
            .append('|')
            .append(encoded(field.attribute))
            .append('|')
            .append(field.type)
            .append('|')
            .append(String.join(",", field.destinations))
            .append('|')
            .append(valuePolicy(field.valuePolicy))
            .append('\n');
      }
      for (DynamicPolicy.DerivedField field : event.derivedFields) {
        result
            .append("D|")
            .append(encoded(event.id))
            .append('|')
            .append(encoded(field.attribute))
            .append('|')
            .append(encoded(field.expression))
            .append('|')
            .append(field.type)
            .append('|')
            .append(String.join(",", field.destinations))
            .append('|')
            .append(valuePolicy(field.valuePolicy))
            .append('\n');
      }
    }
    for (DynamicPolicy.MessagingEventPolicy event : policy.messagingEventPolicies) {
      if (!event.enabled) {
        continue;
      }
      result
          .append("E|")
          .append(encoded(event.id))
          .append('|')
          .append(encoded(event.scope))
          .append('|')
          .append(encoded("application/json"))
          .append('|')
          .append(encoded("application/json"))
          .append('|')
          .append(event.maxPayloadBytes)
          .append('|')
          .append(encoded(event.eventName))
          .append('|')
          .append(event.log.enabled)
          .append('|')
          .append(encoded(event.log.severity))
          .append('|')
          .append(encoded(event.log.body))
          .append('\n');
      for (DynamicPolicy.MessagingCondition condition : event.conditions) {
        result
            .append("C|")
            .append(encoded(event.id))
            .append('|')
            .append(compiledMessagingSource(condition.source, false))
            .append('|')
            .append(encoded(compiledMessagingSelector(condition.source, condition.path, false)))
            .append('|')
            .append(condition.operator)
            .append('|')
            .append(encodedList(condition.values))
            .append('\n');
      }
      for (DynamicPolicy.StaticAttribute attribute : event.staticAttributes) {
        result
            .append("S|")
            .append(encoded(event.id))
            .append('|')
            .append(encoded(attribute.attribute))
            .append('|')
            .append(encoded(attribute.value))
            .append('|')
            .append(attribute.type)
            .append('|')
            .append(String.join(",", attribute.destinations))
            .append('\n');
      }
      for (DynamicPolicy.MessagingField field : event.fields) {
        result
            .append("F|")
            .append(encoded(event.id))
            .append('|')
            .append(compiledMessagingSource(field.source, true))
            .append('|')
            .append(encoded(compiledMessagingSelector(field.source, field.path, true)))
            .append('|')
            .append(encoded(field.attribute))
            .append('|')
            .append(field.type)
            .append('|')
            .append(String.join(",", field.destinations))
            .append('|')
            .append(valuePolicy(field.valuePolicy))
            .append('\n');
      }
    }
    for (DynamicPolicy.EventMetricPolicy metric : policy.eventMetricPolicies) {
      if (!metric.enabled) {
        continue;
      }
      result
          .append("I|")
          .append(encoded(metric.eventName))
          .append('|')
          .append(encoded(metric.name))
          .append('|')
          .append(metric.instrument)
          .append('|')
          .append(encoded(metric.unit))
          .append('|')
          .append(encoded(metric.description))
          .append('|')
          .append(encoded(metric.valueField))
          .append('|')
          .append(encodedList(metric.dimensions))
          .append('|')
          .append(encodedList(metric.standardAttributes))
          .append('|')
          .append(
              metric.buckets.stream()
                  .map(String::valueOf)
                  .collect(Collectors.joining(",")))
          .append('\n');
    }
    for (DynamicPolicy.MessagingMetricPolicy metric : policy.messagingMetricPolicies) {
      if (!metric.enabled) {
        continue;
      }
      result
          .append("I|")
          .append(encoded(metric.eventName))
          .append('|')
          .append(encoded(metric.name))
          .append('|')
          .append(metric.instrument)
          .append('|')
          .append(encoded(metric.unit))
          .append('|')
          .append(encoded(metric.description))
          .append('|')
          .append(encoded(metric.valueField))
          .append('|')
          .append(encodedList(metric.dimensions))
          .append('|')
          .append(encodedList(List.of()))
          .append('|')
          .append(
              metric.buckets.stream()
                  .map(String::valueOf)
                  .collect(Collectors.joining(",")))
          .append('\n');
    }
    return result.toString();
  }

  private static String compiledMessagingSource(String source, boolean field) {
    return switch (source) {
      case "PAYLOAD" -> "REQUEST_BODY";
      case "MESSAGE_PROPERTY" -> "REQUEST_QUERY";
      case "DESTINATION" -> field ? "REQUEST_HEADER" : "REQUEST_PATH";
      case "MESSAGE_KEY", "MESSAGE_HEADER" -> "REQUEST_HEADER";
      default -> throw new IllegalArgumentException("unsupported messaging source " + source);
    };
  }

  private static String compiledMessagingSelector(
      String source, String selector, boolean field) {
    return switch (source) {
      case "PAYLOAD" -> selector;
      case "MESSAGE_HEADER" -> "message-header." + selector;
      case "MESSAGE_PROPERTY" -> "message-property." + selector;
      case "MESSAGE_KEY" -> "message-key";
      case "DESTINATION" -> field ? "message-destination" : "";
      default -> throw new IllegalArgumentException("unsupported messaging source " + source);
    };
  }

  private static String valuePolicy(DynamicPolicy.ValuePolicy policy) {
    String ranges =
        policy.ranges.stream()
            .map(
                range ->
                    (range.max == null ? "*" : String.valueOf(range.max))
                        + ':'
                        + encoded(range.label))
            .collect(Collectors.joining(","));
    return policy.type
        + '|'
        + encodedList(policy.allowed)
        + '|'
        + encoded(policy.fallback)
        + '|'
        + ranges;
  }

  private static String encodedList(List<String> values) {
    return encoded(String.join(LIST_SEPARATOR, values));
  }

  private static String encoded(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
