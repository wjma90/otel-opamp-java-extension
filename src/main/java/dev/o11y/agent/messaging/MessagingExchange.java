package dev.o11y.agent.messaging;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** One bounded producer or consumer operation evaluated against the active policy generation. */
public final class MessagingExchange {
  private static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  private static final String HEADER_PREFIX = "message-header.";
  private static final String PROPERTY_PREFIX = "message-property.";
  private static final String DESTINATION = "message-destination";
  private static final String MESSAGE_KEY = "message-key";
  private static final int MAX_VALUE_CHARS = 256;
  private static final ThreadLocal<Deque<OwnershipKey>> ACTIVE =
      ThreadLocal.withInitial(ArrayDeque::new);
  private static final State NOOP =
      new State(
          false,
          "",
          "",
          "",
          new byte[0],
          Map.of(),
          Map.of(),
          Context.root(),
          null);
  private static final CaptureRequirements NO_REQUIREMENTS =
      new CaptureRequirements(false, "", "", "", List.of(), List.of(), 0);

  private MessagingExchange() {}

  public static State capture(
      String scope,
      String destination,
      Object messageIdentity,
      Object key,
      Map<String, List<String>> headers,
      Map<String, String> properties,
      Object payload) {
    return capture(
        requirements(scope, destination),
        messageIdentity,
        key,
        headers,
        properties,
        payload);
  }

  /**
   * Resolves the exact selectors needed by the active generation before a client message is read.
   * Application headers and properties that are not present here must never be copied.
   */
  public static CaptureRequirements requirements(String scope, String destination) {
    String normalizedScope = normalizeScope(scope);
    String normalizedDestination = destination == null ? "" : destination.trim();
    if (normalizedScope.isEmpty() || normalizedDestination.isEmpty()) {
      return NO_REQUIREMENTS;
    }
    String operation = normalizedScope.endsWith("_PRODUCER") ? "PRODUCE" : "CONSUME";
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    if (!HttpBodyPolicyEngine.hasCandidate(
        normalizedScope, operation, normalizedDestination, generation)) {
      return NO_REQUIREMENTS;
    }
    List<String> requiredHeaders =
        HttpBodyPolicyEngine.requiredRequestHeaderNames(
            normalizedScope, operation, normalizedDestination, generation);
    List<String> requiredProperties =
        HttpBodyPolicyEngine.requiredRequestQueryNames(
            normalizedScope, operation, normalizedDestination, generation);
    int limit =
        HttpBodyPolicyEngine
            .capturePlan(normalizedScope, operation, normalizedDestination, generation)
            .requestLimit();
    return new CaptureRequirements(
        true,
        generation,
        normalizedScope,
        normalizedDestination,
        requiredHeaders,
        requiredProperties,
        limit);
  }

  public static State capture(
      CaptureRequirements requirements,
      Object messageIdentity,
      Object key,
      Map<String, List<String>> headers,
      Map<String, String> properties,
      Object payload) {
    if (requirements == null || !requirements.active) {
      return NOOP;
    }

    OwnershipKey ownership =
        new OwnershipKey(requirements.scope, System.identityHashCode(messageIdentity));
    Deque<OwnershipKey> active = ACTIVE.get();
    if (active.contains(ownership)) {
      return NOOP;
    }
    active.push(ownership);
    try {
      Map<String, List<String>> selectedHeaders =
          selectedHeaders(
              requirements.destination, key, headers, requirements.requiredHeaders);
      Map<String, List<String>> selectedProperties =
          selectedProperties(properties, requirements.requiredProperties);
      return new State(
          true,
          requirements.generation,
          requirements.scope,
          requirements.destination,
          boundedPayload(payload, requirements.payloadLimit),
          selectedHeaders,
          selectedProperties,
          Context.current(),
          ownership);
    } catch (Throwable failure) {
      release(ownership);
      return NOOP;
    }
  }

  private static String normalizeScope(String scope) {
    String normalized = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
    return Set.of("KAFKA_PRODUCER", "KAFKA_CONSUMER", "JMS_PRODUCER", "JMS_CONSUMER")
            .contains(normalized)
        ? normalized
        : "";
  }

  private static Map<String, List<String>> selectedHeaders(
      String destination,
      Object key,
      Map<String, List<String>> headers,
      List<String> required) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    Set<String> selected = Set.copyOf(required);
    if (selected.contains(DESTINATION)) {
      result.put(DESTINATION, List.of(truncate(destination)));
    }
    if (key != null && selected.contains(MESSAGE_KEY)) {
      result.put(MESSAGE_KEY, List.of(truncate(String.valueOf(key))));
    }
    if (headers != null) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          continue;
        }
        String name = HEADER_PREFIX + entry.getKey().toLowerCase(Locale.ROOT);
        if (!selected.contains(name)) {
          continue;
        }
        List<String> values =
            entry.getValue().stream()
                .filter(value -> value != null)
                .limit(4)
                .map(MessagingExchange::truncate)
                .toList();
        if (!values.isEmpty()) {
          result.put(name, values);
        }
      }
    }
    return Map.copyOf(result);
  }

  private static Map<String, List<String>> selectedProperties(
      Map<String, String> properties, List<String> required) {
    if (properties == null || properties.isEmpty()) {
      return Map.of();
    }
    Set<String> selected = Set.copyOf(required);
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      String name = PROPERTY_PREFIX + entry.getKey();
      if (entry.getValue() != null && selected.contains(name)) {
        result.put(name, List.of(truncate(entry.getValue())));
      }
    }
    return Map.copyOf(result);
  }

  private static byte[] boundedPayload(Object payload, int limit) {
    if (limit <= 0 || payload == null) {
      return new byte[0];
    }
    byte[] bytes;
    if (payload instanceof byte[] array) {
      bytes = array;
    } else if (payload instanceof String text) {
      bytes = text.getBytes(StandardCharsets.UTF_8);
    } else {
      return new byte[0];
    }
    int boundedLength = Math.min(bytes.length, limit + 1);
    return Arrays.copyOf(bytes, boundedLength);
  }

  private static String truncate(String value) {
    return value.substring(0, Math.min(value.length(), MAX_VALUE_CHARS));
  }

  /** Policy-derived, immutable capture plan used by reflection helpers. */
  public static final class CaptureRequirements {
    private final boolean active;
    private final String generation;
    private final String scope;
    private final String destination;
    private final List<String> requiredHeaders;
    private final List<String> requiredProperties;
    private final int payloadLimit;

    private CaptureRequirements(
        boolean active,
        String generation,
        String scope,
        String destination,
        List<String> requiredHeaders,
        List<String> requiredProperties,
        int payloadLimit) {
      this.active = active;
      this.generation = generation;
      this.scope = scope;
      this.destination = destination;
      this.requiredHeaders = List.copyOf(requiredHeaders);
      this.requiredProperties = List.copyOf(requiredProperties);
      this.payloadLimit = payloadLimit;
    }

    public boolean active() {
      return active;
    }

    public boolean messageKeyRequired() {
      return requiredHeaders.contains(MESSAGE_KEY);
    }

    public boolean payloadRequired() {
      return payloadLimit > 0;
    }

    public List<String> messageHeaderNames() {
      return requiredHeaders.stream()
          .filter(name -> name.startsWith(HEADER_PREFIX))
          .map(name -> name.substring(HEADER_PREFIX.length()))
          .collect(Collectors.toUnmodifiableList());
    }

    public List<String> messagePropertyNames() {
      return requiredProperties.stream()
          .filter(name -> name.startsWith(PROPERTY_PREFIX))
          .map(name -> name.substring(PROPERTY_PREFIX.length()))
          .collect(Collectors.toUnmodifiableList());
    }
  }

  private static void release(OwnershipKey key) {
    if (key == null) {
      return;
    }
    Deque<OwnershipKey> active = ACTIVE.get();
    active.removeFirstOccurrence(key);
    if (active.isEmpty()) {
      ACTIVE.remove();
    }
  }

  /** Captured policy inputs that are emitted only when the intercepted operation succeeds. */
  public static final class State {
    private final boolean owner;
    private final String generation;
    private final String scope;
    private final String destination;
    private final byte[] payload;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> properties;
    private final Context context;
    private final OwnershipKey ownership;
    private final AtomicBoolean finished = new AtomicBoolean();

    private State(
        boolean owner,
        String generation,
        String scope,
        String destination,
        byte[] payload,
        Map<String, List<String>> headers,
        Map<String, List<String>> properties,
        Context context,
        OwnershipKey ownership) {
      this.owner = owner;
      this.generation = generation;
      this.scope = scope;
      this.destination = destination;
      this.payload = payload;
      this.headers = headers;
      this.properties = properties;
      this.context = context;
      this.ownership = ownership;
    }

    public int complete(Throwable error) {
      if (!owner || !finished.compareAndSet(false, true)) {
        return 0;
      }
      try {
        if (error != null) {
          return 0;
        }
        Context active = Context.current();
        Context effective = Span.fromContext(active).isRecording() ? active : context;
        String operation = scope.endsWith("_PRODUCER") ? "PRODUCE" : "CONSUME";
        return HttpBodyPolicyEngine.process(
            scope,
            operation,
            destination,
            "application/json",
            "identity",
            payload,
            200,
            "application/json",
            "identity",
            new byte[0],
            headers,
            Map.of(),
            properties,
            effective,
            generation);
      } finally {
        release(ownership);
      }
    }

    public void abort() {
      if (owner && finished.compareAndSet(false, true)) {
        release(ownership);
      }
    }
  }

  private record OwnershipKey(String scope, int messageIdentity) {}
}
