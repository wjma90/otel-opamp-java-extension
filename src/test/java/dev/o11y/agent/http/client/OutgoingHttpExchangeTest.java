package dev.o11y.agent.http.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.policy.PolicyState;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OutgoingHttpExchangeTest {
  @AfterEach
  void clearProperties() {
    String generation = System.getProperty(PolicyState.ACTIVE_GENERATION_PROPERTY, "");
    for (String property :
        new String[] {
          PolicyState.REQUEST_HEADERS_PROPERTY,
          PolicyState.RESPONSE_HEADERS_PROPERTY,
          PolicyState.OUTGOING_REQUEST_HEADERS_PROPERTY,
          PolicyState.OUTGOING_RESPONSE_HEADERS_PROPERTY,
          PolicyState.BODY_COMPILED_PROPERTY
        }) {
      System.clearProperty(property);
      if (!generation.isBlank()) {
        System.clearProperty(PolicyState.generationProperty(property, generation));
      }
    }
    System.clearProperty(PolicyState.ACTIVE_GENERATION_PROPERTY);
  }

  @Test
  void claimsOnlyTheOutermostSupportedClientAndReleasesItOnAbort() throws Exception {
    PolicyState.applyJson(resource("http-outgoing-rates-client.json"));

    OutgoingHttpExchange outer =
        OutgoingHttpExchange.start(
            "POST",
            "http://rates-service/api/rates/quote?source=PEN",
            Map.of("Content-Type", java.util.List.of("application/json")));
    OutgoingHttpExchange nested =
        OutgoingHttpExchange.start("POST", "/api/rates/quote", Map.of());

    assertTrue(outer.isOwner());
    assertEquals(65536, outer.captureLimit());
    assertEquals(65536, outer.responseCaptureLimit(200));
    assertEquals(0, outer.responseCaptureLimit(500));
    assertFalse(nested.isOwner());

    outer.abort();
    OutgoingHttpExchange next =
        OutgoingHttpExchange.start("POST", "/api/rates/quote", Map.of());
    assertTrue(next.isOwner());
    next.abort();
  }

  @Test
  void suppressesOnlyTheSameLogicalTransportAndAllowsADifferentNestedCall() throws Exception {
    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [
            {"name": "x-client-channel", "direction": "OUTGOING"}
          ]
        }
        """);

    OutgoingHttpExchange outer =
        OutgoingHttpExchange.start("GET", "https://example.test/outer", Map.of());
    OutgoingHttpExchange duplicate =
        OutgoingHttpExchange.start("GET", "https://example.test/outer", Map.of());
    OutgoingHttpExchange nested =
        OutgoingHttpExchange.start("GET", "https://example.test/nested", Map.of());

    assertTrue(outer.isOwner());
    assertFalse(duplicate.isOwner());
    assertTrue(nested.isOwner());

    nested.abort();
    outer.abort();
  }

  @Test
  void retainsOnlyConfiguredAndProtocolHeaders() throws Exception {
    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [
            {"name": "x-client-channel", "direction": "OUTGOING"}
          ]
        }
        """);

    OutgoingHttpExchange exchange =
        OutgoingHttpExchange.start(
            "GET",
            "https://example.test/resource",
            Map.of(
                "Authorization", List.of("Bearer must-not-be-retained"),
                "Cookie", List.of("session=must-not-be-retained"),
                "Content-Type", List.of("application/json"),
                "X-Client-Channel", List.of("WEB")));

    Field field = OutgoingHttpExchange.class.getDeclaredField("requestHeaders");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, List<String>> retained = (Map<String, List<String>>) field.get(exchange);
    assertEquals(Map.of("content-type", List.of("application/json"), "x-client-channel", List.of("WEB")), retained);
    exchange.abort();
  }

  @Test
  void activatesAndRetainsOnlyEventSelectedHeadersAndQueryWithoutBodies() throws Exception {
    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.4",
          "bodyEventPolicies": [{
            "id": "metadata-only-outgoing",
            "ruleName": "Metadata only outgoing",
            "direction": "OUTGOING",
            "eventName": "metadata-only-outgoing",
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/resource"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["POST"]}
            ],
            "fields": [
              {
                "source": "REQUEST_HEADER",
                "path": "x-customer-tier",
                "attribute": "customer.tier",
                "type": "STRING",
                "destinations": ["SPAN"]
              },
              {
                "source": "RESPONSE_HEADER",
                "path": "x-operation-result",
                "attribute": "operation.result",
                "type": "STRING",
                "destinations": ["LOG"]
              },
              {
                "source": "REQUEST_QUERY",
                "path": "campaign",
                "attribute": "request.campaign",
                "type": "STRING",
                "destinations": ["SPAN"]
              }
            ]
          }]
        }
        """);

    OutgoingHttpExchange exchange =
        OutgoingHttpExchange.start(
            "POST",
            "https://example.test/resource?campaign=JULY&Campaign=discarded",
            Map.of(
                "Authorization", List.of("must-not-be-retained"),
                "X-Customer-Tier", List.of("PREMIUM")));

    assertTrue(exchange.isOwner());
    assertEquals(0, exchange.captureLimit());
    assertEquals(Map.of(), reflectedMap(exchange, "requestHeaders"));
    assertEquals(
        Map.of("x-customer-tier", List.of("PREMIUM")),
        reflectedMap(exchange, "eventRequestHeaders"));
    assertEquals(
        Map.of("campaign", List.of("JULY")), reflectedMap(exchange, "requestQuery"));
    exchange.abort();
  }

  @Test
  void keepsDirectAndEventHeaderBudgetsIndependentBeyondSixteenCombinedHeaders()
      throws Exception {
    List<String> requestHeaderConfig = new ArrayList<>();
    List<String> responseHeaderConfig = new ArrayList<>();
    List<String> conditions = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
    Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    conditions.add(
        "{\"source\":\"REQUEST_PATH\",\"operator\":\"EQUALS\","
            + "\"values\":[\"/resource\"]}");
    conditions.add(
        "{\"source\":\"REQUEST_METHOD\",\"operator\":\"EQUALS\","
            + "\"values\":[\"GET\"]}");
    for (int index = 0; index < 16; index++) {
      String directRequest = "x-direct-request-" + index;
      String directResponse = "x-direct-response-" + index;
      String eventRequest = "x-event-request-" + index;
      String eventResponse = "x-event-response-" + index;
      requestHeaderConfig.add(
          "{\"name\":\"" + directRequest + "\",\"direction\":\"OUTGOING\"}");
      responseHeaderConfig.add(
          "{\"name\":\"" + directResponse + "\",\"direction\":\"OUTGOING\"}");
      requestHeaders.put(directRequest, List.of("direct-request-" + index));
      requestHeaders.put(
          eventRequest, List.of("event-request-" + index, "ignored-request-" + index));
      responseHeaders.put(directResponse, List.of("direct-response-" + index));
      responseHeaders.put(
          eventResponse, List.of("event-response-" + index, "ignored-response-" + index));
      fields.add(metadataField("REQUEST_HEADER", eventRequest, "event.request." + index));
      fields.add(metadataField("RESPONSE_HEADER", eventResponse, "event.response." + index));
    }
    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.4",
          "requestHeaders": [%s],
          "responseHeaders": [%s],
          "bodyEventPolicies": [{
            "id": "independent-header-budgets",
            "ruleName": "Independent header budgets",
            "direction": "OUTGOING",
            "eventName": "independent-header-budgets",
            "conditions": [%s],
            "fields": [%s]
          }]
        }
        """
            .formatted(
                String.join(",", requestHeaderConfig),
                String.join(",", responseHeaderConfig),
                String.join(",", conditions),
                String.join(",", fields)));

    RecordingSpan span = new RecordingSpan();
    Scope scope = span.makeCurrent();
    try {
      OutgoingHttpExchange exchange =
          OutgoingHttpExchange.start(
              "GET", "https://example.test/resource", requestHeaders);
      assertEquals(16, reflectedMap(exchange, "requestHeaders").size());
      assertEquals(16, reflectedMap(exchange, "eventRequestHeaders").size());

      exchange.complete(200, responseHeaders, new byte[0]);
    } finally {
      scope.close();
    }

    for (int index = 0; index < 16; index++) {
      assertEquals("event-request-" + index, span.attributes.get("event.request." + index));
      assertEquals("event-response-" + index, span.attributes.get("event.response." + index));
    }
  }

  @Test
  void clearsAndZeroesBodyBuffersAndRejectsLateWrites() throws Exception {
    PolicyState.applyJson(resource("http-outgoing-rates-client.json"));
    OutgoingHttpExchange exchange =
        OutgoingHttpExchange.start(
            "POST",
            "https://example.test/api/rates/quote",
            Map.of("Content-Type", List.of("application/json")));
    exchange.captureRequest("sensitive-body".getBytes(StandardCharsets.UTF_8));

    Field bodyField = OutgoingHttpExchange.class.getDeclaredField("requestBody");
    bodyField.setAccessible(true);
    Object body = bodyField.get(exchange);
    Field bytesField = body.getClass().getDeclaredField("bytes");
    bytesField.setAccessible(true);
    byte[] allocated = (byte[]) bytesField.get(body);
    assertTrue(Arrays.stream(toUnsignedInts(allocated)).anyMatch(value -> value != 0));

    exchange.abort();
    exchange.captureRequest("late-write".getBytes(StandardCharsets.UTF_8));

    assertNull(bytesField.get(body));
    assertTrue(Arrays.stream(toUnsignedInts(allocated)).allMatch(value -> value == 0));
    assertEquals(0, exchange.remainingRequestCaptureBytes());
  }

  @Test
  void doesNotActivateForAnIncomingOnlyGenericHeader() throws Exception {
    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [{"name": "x-correlation-id"}]
        }
        """);

    OutgoingHttpExchange incomingOnly =
        OutgoingHttpExchange.start(
            "GET", "http://rates-service/api/rates", Map.of("x-correlation-id", java.util.List.of("one")));
    assertFalse(incomingOnly.isOwner());

    PolicyState.applyJson(
        """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [
            {"name": "x-correlation-id", "direction": "OUTGOING"}
          ]
        }
        """);
    OutgoingHttpExchange outgoing =
        OutgoingHttpExchange.start(
            "GET", "http://rates-service/api/rates", Map.of("x-correlation-id", java.util.List.of("one")));
    assertTrue(outgoing.isOwner());
    outgoing.abort();
  }

  @Test
  void replaysEveryByteIncludingTheOverflowSentinel() throws Exception {
    byte[] shortBody = "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
    OutgoingHttpExchange.ReplayBody shortReplay =
        OutgoingHttpExchange.readAndReplay(new ByteArrayInputStream(shortBody), 64);
    assertArrayEquals(shortBody, shortReplay.captured());
    byte[] callerCopy = shortReplay.captured();
    callerCopy[0] = 0;
    assertArrayEquals(shortBody, shortReplay.stream().readAllBytes());
    assertTrue(
        Arrays.stream(toUnsignedInts(shortReplay.captured())).allMatch(value -> value == 0));

    byte[] largeBody = "0123456789".getBytes(StandardCharsets.UTF_8);
    OutgoingHttpExchange.ReplayBody largeReplay =
        OutgoingHttpExchange.readAndReplay(new ByteArrayInputStream(largeBody), 4);
    assertEquals(5, largeReplay.captured().length);
    assertArrayEquals(largeBody, largeReplay.stream().readAllBytes());
    assertTrue(
        Arrays.stream(toUnsignedInts(largeReplay.captured())).allMatch(value -> value == 0));
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> reflectedMap(
      OutgoingHttpExchange exchange, String name) throws Exception {
    Field field = OutgoingHttpExchange.class.getDeclaredField(name);
    field.setAccessible(true);
    return (Map<String, List<String>>) field.get(exchange);
  }

  private static int[] toUnsignedInts(byte[] bytes) {
    int[] result = new int[bytes.length];
    for (int index = 0; index < bytes.length; index++) {
      result[index] = Byte.toUnsignedInt(bytes[index]);
    }
    return result;
  }

  private static String metadataField(String source, String path, String attribute) {
    return "{\"source\":\""
        + source
        + "\",\"path\":\""
        + path
        + "\",\"attribute\":\""
        + attribute
        + "\",\"type\":\"STRING\",\"destinations\":[\"SPAN\"]}";
  }

  private static final class RecordingSpan implements Span {
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
      attributes.put(key.getKey(), value);
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
      return this;
    }

    @Override
    public Span addEvent(
        String name, Attributes attributes, long timestamp, TimeUnit unit) {
      return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
      return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
      return this;
    }

    @Override
    public Span updateName(String name) {
      return this;
    }

    @Override
    public void end() {}

    @Override
    public void end(long timestamp, TimeUnit unit) {}

    @Override
    public SpanContext getSpanContext() {
      return SpanContext.getInvalid();
    }

    @Override
    public boolean isRecording() {
      return true;
    }
  }
}
