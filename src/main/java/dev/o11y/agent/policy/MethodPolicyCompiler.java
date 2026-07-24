package dev.o11y.agent.policy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

public final class MethodPolicyCompiler {
  private MethodPolicyCompiler() {}

  public static String compile(DynamicPolicy policy) {
    StringBuilder output = new StringBuilder("V|1\n");
    for (DynamicPolicy.MethodPolicy method : policy.methodPolicies) {
      if (!method.enabled) {
        continue;
      }
      output
          .append("M|")
          .append(encoded(method.id))
          .append('|')
          .append(encoded(method.className))
          .append('|')
          .append(encoded(method.methodName))
          .append('|')
          .append(method.log.enabled)
          .append('|')
          .append(encoded(method.log.severity))
          .append('|')
          .append(encoded(method.log.body))
          .append('\n');
      for (DynamicPolicy.Capture capture : method.captures) {
        output
            .append("C|")
            .append(encoded(method.id))
            .append('|')
            .append(capture.source)
            .append('|')
            .append(capture.argumentIndex)
            .append('|')
            .append(encoded(DynamicPolicy.normalizedObjectPath(capture.path)))
            .append('|')
            .append(capture.constant)
            .append('|')
            .append(encoded(capture.attribute))
            .append('|')
            .append(capture.type)
            .append('|')
            .append(String.join(",", capture.destinations))
            .append('|')
            .append(capture.valuePolicy.type)
            .append('|')
            .append(
                encoded(
                    String.join("\u001f", capture.valuePolicy.allowed)))
            .append('|')
            .append(encoded(capture.valuePolicy.fallback))
            .append('|')
            .append(encoded(ranges(capture.valuePolicy)))
            .append('\n');
      }
      for (DynamicPolicy.MethodMetric metric : method.metrics) {
        output
            .append("I|")
            .append(encoded(method.id))
            .append('|')
            .append(encoded(metric.name))
            .append('|')
            .append(metric.instrument)
            .append('|')
            .append(encoded(metric.unit))
            .append('|')
            .append(encoded(metric.description))
            .append('|')
            .append(metric.value.source)
            .append('|')
            .append(metric.value.argumentIndex)
            .append('|')
            .append(encoded(DynamicPolicy.normalizedObjectPath(metric.value.path)))
            .append('|')
            .append(metric.value.constant)
            .append('|')
            .append(
                metric.buckets.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")))
            .append('\n');
      }
    }
    return output.toString();
  }

  private static String ranges(DynamicPolicy.ValuePolicy policy) {
    return policy.ranges.stream()
        .map(
            range ->
                (range.max == null ? "*" : range.max)
                    + ":"
                    + encoded(range.label))
        .collect(Collectors.joining(","));
  }

  private static String encoded(String value) {
    String safe = value == null ? "" : value;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(safe.getBytes(StandardCharsets.UTF_8));
  }
}
