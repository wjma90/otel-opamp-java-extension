package dev.o11y.agent.policy;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Holds immutable effective policy snapshots and switches generations atomically. */
public final class PolicyState {
  public static final String ACTIVE_GENERATION_PROPERTY =
      "o11y.dynamic.policy.active-generation";
  public static final String REQUEST_HEADERS_PROPERTY = "o11y.dynamic.request.headers";
  public static final String RESPONSE_HEADERS_PROPERTY = "o11y.dynamic.response.headers";
  public static final String OUTGOING_REQUEST_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.request.headers";
  public static final String OUTGOING_RESPONSE_HEADERS_PROPERTY =
      "o11y.dynamic.http.outgoing.response.headers";
  public static final String BODY_POLICY_PROPERTY = "o11y.dynamic.body.policy";
  public static final String BODY_COMPILED_PROPERTY = "o11y.dynamic.body.compiled";
  public static final String METHOD_POLICY_PROPERTY = "o11y.dynamic.method.policy";
  public static final String METHOD_COMPILED_PROPERTY = "o11y.dynamic.method.compiled";
  public static final String OUTGOING_METRICS_PROPERTY =
      "o11y.dynamic.http.outgoing-metrics.enabled";
  private static final int RETAINED_GENERATIONS = 16;

  private static final AtomicLong GENERATIONS = new AtomicLong();
  private static final Object UPDATE_LOCK = new Object();
  private static final Snapshot EMPTY =
      new Snapshot("", "", Map.of(), new DynamicPolicy(), "", "", "");
  private static final ConcurrentHashMap<String, Snapshot> SNAPSHOTS =
      new ConcurrentHashMap<>();
  private static final Deque<String> GENERATION_ORDER = new ArrayDeque<>();

  private PolicyState() {}

  public static DynamicPolicy current() {
    return currentSnapshot().effectivePolicy();
  }

  public static Snapshot currentSnapshot() {
    String generation = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
    return SNAPSHOTS.getOrDefault(generation, EMPTY);
  }

  public static Snapshot applyJson(String source) throws Exception {
    return apply(PolicySet.parse(source));
  }

  public static Snapshot apply(PolicySet policySet) {
    synchronized (UPDATE_LOCK) {
      return applyLocked(policySet);
    }
  }

  private static Snapshot applyLocked(PolicySet policySet) {
    DynamicPolicy effective = policySet.effectivePolicy();
    String compiled = MethodPolicyCompiler.compile(effective);
    String compiledBody = BodyPolicyCompiler.compile(effective);
    String requestHeaders = names(effective.requestHeaders, "INCOMING");
    String responseHeaders = names(effective.responseHeaders, "INCOMING");
    String outgoingRequestHeaders = names(effective.requestHeaders, "OUTGOING");
    String outgoingResponseHeaders = names(effective.responseHeaders, "OUTGOING");
    String outgoingMetrics =
        Boolean.toString(
            effective.metricPolicies.stream()
                .anyMatch(
                    metric ->
                        metric.enabled && "OUTGOING".equalsIgnoreCase(metric.direction)));

    // This is the last validation step and locks immutable OTel instrument
    // identities. It does not mutate runtime policy state on rejection.
    PolicyValidator.validateAndLock(effective);

    String generation = Long.toUnsignedString(GENERATIONS.incrementAndGet());
    Snapshot snapshot =
        new Snapshot(
            generation,
            policySet.revision(),
            policySet.policies(),
            effective,
            policySet.effectiveJson(),
            compiled,
            compiledBody);

    stage(REQUEST_HEADERS_PROPERTY, generation, requestHeaders);
    stage(RESPONSE_HEADERS_PROPERTY, generation, responseHeaders);
    stage(OUTGOING_REQUEST_HEADERS_PROPERTY, generation, outgoingRequestHeaders);
    stage(OUTGOING_RESPONSE_HEADERS_PROPERTY, generation, outgoingResponseHeaders);
    stage(BODY_POLICY_PROPERTY, generation, policySet.effectiveJson());
    stage(BODY_COMPILED_PROPERTY, generation, compiledBody);
    stage(METHOD_POLICY_PROPERTY, generation, policySet.effectiveJson());
    stage(METHOD_COMPILED_PROPERTY, generation, compiled);
    stage(OUTGOING_METRICS_PROPERTY, generation, outgoingMetrics);
    SNAPSHOTS.put(generation, snapshot);
    GENERATION_ORDER.addLast(generation);

    // The active generation is the sole commit point read by every runtime.
    System.setProperty(ACTIVE_GENERATION_PROPERTY, generation);

    // Preserve the original property names for legacy integrations. Runtime
    // components in this project use their generation-scoped counterparts.
    System.setProperty(REQUEST_HEADERS_PROPERTY, requestHeaders);
    System.setProperty(RESPONSE_HEADERS_PROPERTY, responseHeaders);
    System.setProperty(OUTGOING_REQUEST_HEADERS_PROPERTY, outgoingRequestHeaders);
    System.setProperty(OUTGOING_RESPONSE_HEADERS_PROPERTY, outgoingResponseHeaders);
    System.setProperty(BODY_POLICY_PROPERTY, policySet.effectiveJson());
    System.setProperty(BODY_COMPILED_PROPERTY, compiledBody);
    System.setProperty(METHOD_POLICY_PROPERTY, policySet.effectiveJson());
    System.setProperty(METHOD_COMPILED_PROPERTY, compiled);
    System.setProperty(OUTGOING_METRICS_PROPERTY, outgoingMetrics);
    pruneOldGenerations();
    return snapshot;
  }

  private static void pruneOldGenerations() {
    while (GENERATION_ORDER.size() > RETAINED_GENERATIONS) {
      String expired = GENERATION_ORDER.removeFirst();
      SNAPSHOTS.remove(expired);
      clearGeneration(expired);
    }
  }

  private static void clearGeneration(String generation) {
    System.clearProperty(generationProperty(REQUEST_HEADERS_PROPERTY, generation));
    System.clearProperty(generationProperty(RESPONSE_HEADERS_PROPERTY, generation));
    System.clearProperty(generationProperty(OUTGOING_REQUEST_HEADERS_PROPERTY, generation));
    System.clearProperty(generationProperty(OUTGOING_RESPONSE_HEADERS_PROPERTY, generation));
    System.clearProperty(generationProperty(BODY_POLICY_PROPERTY, generation));
    System.clearProperty(generationProperty(BODY_COMPILED_PROPERTY, generation));
    System.clearProperty(generationProperty(METHOD_POLICY_PROPERTY, generation));
    System.clearProperty(generationProperty(METHOD_COMPILED_PROPERTY, generation));
    System.clearProperty(generationProperty(OUTGOING_METRICS_PROPERTY, generation));
  }

  private static void stage(String property, String generation, String value) {
    System.setProperty(generationProperty(property, generation), value);
  }

  public static String generationProperty(String property, String generation) {
    return property + ".generation." + generation;
  }

  private static String names(List<DynamicPolicy.NamedValue> values, String direction) {
    return values.stream()
        .filter(value -> direction.equalsIgnoreCase(value.direction))
        .map(value -> value.name.toLowerCase(Locale.ROOT).trim())
        .distinct()
        .reduce((left, right) -> left + "," + right)
        .orElse("");
  }

  static void resetForTest() {
    synchronized (UPDATE_LOCK) {
      resetForTestLocked();
    }
  }

  private static void resetForTestLocked() {
    for (String generation : SNAPSHOTS.keySet()) {
      clearGeneration(generation);
    }
    System.clearProperty(ACTIVE_GENERATION_PROPERTY);
    System.clearProperty(REQUEST_HEADERS_PROPERTY);
    System.clearProperty(RESPONSE_HEADERS_PROPERTY);
    System.clearProperty(OUTGOING_REQUEST_HEADERS_PROPERTY);
    System.clearProperty(OUTGOING_RESPONSE_HEADERS_PROPERTY);
    System.clearProperty(BODY_POLICY_PROPERTY);
    System.clearProperty(BODY_COMPILED_PROPERTY);
    System.clearProperty(METHOD_POLICY_PROPERTY);
    System.clearProperty(METHOD_COMPILED_PROPERTY);
    System.clearProperty(OUTGOING_METRICS_PROPERTY);
    SNAPSHOTS.clear();
    GENERATION_ORDER.clear();
  }

  static int retainedGenerationCountForTest() {
    return SNAPSHOTS.size();
  }

  public record Snapshot(
      String generation,
      String revision,
      Map<String, PolicySet.PolicyDocument> policies,
      DynamicPolicy effectivePolicy,
      String effectiveJson,
      String compiledMethodPolicy,
      String compiledBodyPolicy) {
    public Snapshot {
      policies =
          policies == null || policies.isEmpty()
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(policies));
    }
  }
}
