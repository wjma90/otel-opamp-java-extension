package dev.o11y.agent.http.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.o11y.agent.policy.PolicyState;
import io.opentelemetry.context.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpBodyPolicyEngineTest {
  @AfterEach
  void clearProperties() {
    String generation = System.getProperty(PolicyState.ACTIVE_GENERATION_PROPERTY, "");
    if (!generation.isBlank()) {
      System.clearProperty(
          PolicyState.generationProperty(PolicyState.BODY_COMPILED_PROPERTY, generation));
    }
    System.clearProperty(PolicyState.BODY_COMPILED_PROPERTY);
    System.clearProperty(PolicyState.ACTIVE_GENERATION_PROPERTY);
    HttpBodyPolicyEngine.clearPolicyCacheForTest();
  }

  @Test
  void combinesRequestAndResponseUsingTheCompiledPolicy() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(resource("http-body-business-event.json"));
    byte[] request =
        """
        {"operation":"EXCHANGE","sourceCurrency":"PEN","targetCurrency":"USD",
         "channel":"WEB","amount":2500}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] approved =
        """
        {"status":"APPROVED","customerType":"STANDARD","rateType":"STANDARD",
         "receivedAmount":718.76}
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(
        65536,
        HttpBodyPolicyEngine.captureLimit(
            "INCOMING", "POST", "/api/exchanges", snapshot.generation()));
    assertEquals(
        1,
        process(request, 201, approved, snapshot.generation()),
        "request and response conditions form one business event");
    assertEquals(
        0,
        process(
            request,
            201,
            approved.clone(),
            snapshot.generation(),
            "REJECTED"),
        "a non-approved response must not emit the event");
  }

  @Test
  void evaluatesDerivedNumericFieldsWithTheJdkOnlyRuntime() throws Exception {
    PolicyState.Snapshot snapshot =
        PolicyState.applyJson(resource("http-body-calculated-total.json"));
    byte[] request = "{\"quantity\":10,\"unitPrice\":15.5}".getBytes(StandardCharsets.UTF_8);
    byte[] response = "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);

    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/orders",
            "application/json",
            "identity",
            request,
            200,
            "application/json",
            "identity",
            response,
            Context.root(),
            snapshot.generation()));
  }

  @Test
  void evaluatesAnOutgoingRequestAndResponseWithTheSameRuntime() throws Exception {
    PolicyState.Snapshot snapshot =
        PolicyState.applyJson(resource("http-outgoing-rates-client.json"));
    byte[] request =
        """
        {"sourceCurrency":"PEN","targetCurrency":"USD","amount":2500,
         "customerType":"SALARY_ACCOUNT"}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] response =
        """
        {"targetAmount":733.14,"rateType":"SALARY_ACCOUNT"}
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(
        65536,
        HttpBodyPolicyEngine.captureLimit(
            "OUTGOING", "POST", "/api/rates/quote", snapshot.generation()));
    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(65536, 65536),
        HttpBodyPolicyEngine.capturePlan(
            "OUTGOING", "POST", "/api/rates/quote", snapshot.generation()));
    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(0, 0),
        HttpBodyPolicyEngine.capturePlanAfterResponse(
            "OUTGOING", "POST", "/api/rates/quote", 500, snapshot.generation()),
        "a rejected status must avoid probing the response body");
    assertEquals(
        0,
        HttpBodyPolicyEngine.captureLimit(
            "INCOMING", "POST", "/api/rates/quote", snapshot.generation()));
    assertEquals(
        0,
        HttpBodyPolicyEngine.captureLimit(
            "OUTGOING", "POST", "/API/rates/quote", snapshot.generation()),
        "HTTP paths are case-sensitive even though methods are not");
    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "OUTGOING",
            "POST",
            "/api/rates/quote",
            "application/json",
            "identity",
            request,
            200,
            "application/json",
            "identity",
            response,
            Context.root(),
            snapshot.generation()));
  }

  @Test
  void correlatesBoundedHeadersAndQueryWithoutRequiringABody() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(httpMetadataEventPolicy());

    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(0, 0),
        HttpBodyPolicyEngine.capturePlan(
            "INCOMING", "GET", "/api/metadata", snapshot.generation()));
    assertEquals(
        List.of("x-customer-tier"),
        HttpBodyPolicyEngine.requiredRequestHeaderNames(
            "INCOMING", "GET", "/api/metadata", snapshot.generation()));
    assertEquals(
        List.of("x-approved-amount"),
        HttpBodyPolicyEngine.requiredResponseHeaderNames(
            "INCOMING", "GET", "/api/metadata", snapshot.generation()));
    assertEquals(
        List.of("campaign"),
        HttpBodyPolicyEngine.requiredRequestQueryNames(
            "INCOMING", "GET", "/api/metadata", snapshot.generation()));
    assertEquals(
        Map.of("campaign", List.of("JULY 2026", "IGNORED", "THIRD", "FOURTH")),
        HttpBodyPolicyEngine.selectQueryParameters(
            "campaign=JULY+2026&campaign=IGNORED&campaign=THIRD&campaign=FOURTH"
                + "&campaign=FIFTH&Campaign=WRONG_CASE&other=discarded",
            List.of("campaign")));

    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/api/metadata",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Map.of("x-customer-tier", List.of("PREMIUM")),
            Map.of("x-approved-amount", List.of("1250.50")),
            Map.of("campaign", List.of("JULY 2026")),
            Context.root(),
            snapshot.generation()));
    assertEquals(
        0,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/api/metadata",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Map.of("x-customer-tier", List.of("STANDARD", "PREMIUM")),
            Map.of("x-approved-amount", List.of("1250.50")),
            Map.of("campaign", List.of("JULY 2026")),
            Context.root(),
            snapshot.generation()),
        "a later request-header value must not override the first value");
    assertEquals(
        0,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/api/metadata",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Map.of("x-customer-tier", List.of("PREMIUM")),
            Map.of("x-approved-amount", List.of("500.00", "1250.50")),
            Map.of("campaign", List.of("JULY 2026")),
            Context.root(),
            snapshot.generation()),
        "a later response-header value must not override the first value");
    assertEquals(
        0,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/api/metadata",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Map.of("x-customer-tier", List.of("PREMIUM")),
            Map.of("x-approved-amount", List.of("1250.50")),
            Map.of("campaign", List.of("OTHER", "JULY 2026")),
            Context.root(),
            snapshot.generation()),
        "a later query value must not override the first value");
    assertEquals(
        0,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/api/metadata",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Map.of(),
            Map.of("x-approved-amount", List.of("1250.50")),
            Map.of("campaign", List.of("JULY 2026")),
            Context.root(),
            snapshot.generation()),
        "a missing header used as an AND condition rejects the event");
  }

  @Test
  void extractsNamedPathParametersFromSpringMetadataOrThePolicyTemplate() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(pathParameterEventPolicy());

    assertEquals(
        List.of("accountId"),
        HttpBodyPolicyEngine.requiredRequestPathParameterNames(
            "INCOMING", "GET", "/accounts/AC-42/transfers", snapshot.generation()));
    assertEquals(
        Map.of("accountId", List.of("AC-42")),
        HttpBodyPolicyEngine.selectRequestPathParameters(
            "INCOMING",
            "GET",
            "/accounts/AC-42/transfers",
            Map.of(),
            snapshot.generation()),
        "the policy template is the safe fallback when a framework reports no route map");
    assertEquals(
        Map.of("accountId", List.of("SPRING-42")),
        HttpBodyPolicyEngine.selectRequestPathParameters(
            "INCOMING",
            "GET",
            "/accounts/AC-42/transfers",
            Map.of("accountId", "SPRING-42", "ignored", "discarded"),
            snapshot.generation()),
        "only declared logical names are retained from Spring MVC metadata");

    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "GET",
            "/accounts/AC-42/transfers",
            "",
            "",
            new byte[0],
            200,
            "",
            "",
            new byte[0],
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("accountId", List.of("AC-42")),
            Context.root(),
            snapshot.generation()));
    assertEquals(
        0,
        HttpBodyPolicyEngine.captureLimit(
            "INCOMING", "GET", "/accounts/AC-42/other", snapshot.generation()));
  }

  @Test
  void returnsSpanDestinationsForAnOwningServerAttributesExtractor() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(pathParameterEventPolicy());
    Map<String, Object> spanAttributes = new LinkedHashMap<>();

    assertEquals(
        1,
        HttpBodyPolicyEngine.processCollectingSpanAttributes(
            "INCOMING",
            "GET",
            "/accounts/AC-42/transfers",
            "",
            "",
            new byte[0],
            200,
            "",
            "",
            new byte[0],
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("accountId", List.of("AC-42")),
            Context.root(),
            snapshot.generation(),
            spanAttributes));
    assertEquals(Map.of("business.account.id", "AC-42"), spanAttributes);
  }

  @Test
  void processesCounterOnlyEventWithoutExtractedFields() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(counterOnlyEventPolicy());

    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(0, 0),
        HttpBodyPolicyEngine.capturePlan(
            "INCOMING", "POST", "/api/exchanges", snapshot.generation()));
    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "",
            "",
            new byte[0],
            201,
            "",
            "",
            new byte[0],
            Context.root(),
            snapshot.generation()));
  }

  @Test
  void abnormalStatusRejectsSuccessConditionsButKeepsRequestOnlyAttemptEvents()
      throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(attemptAndSuccessPolicy());
    byte[] request = "{\"amount\":2500}".getBytes(StandardCharsets.UTF_8);
    byte[] response = "{\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);

    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "application/json",
            "identity",
            request,
            504,
            "application/json",
            "identity",
            response,
            Context.root(),
            snapshot.generation()),
        "the attempt event remains valid while the HTTP 200 success event is rejected");
    assertEquals(
        1,
        HttpBodyPolicyEngine.processWithErrorType(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "application/json",
            "identity",
            request,
            0,
            "",
            "",
            new byte[0],
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Context.root(),
            snapshot.generation(),
            "java.net.UnknownHostException"),
        "a request-only event may describe a transport failure, while a status condition cannot match an absent response");
    assertEquals(
        2,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "application/json",
            "identity",
            request,
            200,
            "application/json",
            "identity",
            response,
            Context.root(),
            snapshot.generation()));
  }

  @Test
  void plansOnlyTheBodySideActuallyUsedByACandidateRule() {
    System.setProperty(
        PolicyState.BODY_COMPILED_PROPERTY,
        compiledPolicy("request-only", 1024, "REQUEST_BODY"));
    HttpBodyPolicyEngine.clearPolicyCacheForTest();

    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(1024, 0),
        HttpBodyPolicyEngine.capturePlan("OUTGOING", "POST", "/api/concurrent", ""));

    System.setProperty(
        PolicyState.BODY_COMPILED_PROPERTY,
        compiledPolicy("response-only", 2048, "RESPONSE_BODY"));
    HttpBodyPolicyEngine.clearPolicyCacheForTest();

    assertEquals(
        new HttpBodyPolicyEngine.CapturePlan(0, 2048),
        HttpBodyPolicyEngine.capturePlan("OUTGOING", "POST", "/api/concurrent", ""));
  }

  @Test
  void acceptsLogicalAndWireGzipBodiesButRejectsTruncatedBodies() throws Exception {
    PolicyState.Snapshot snapshot = PolicyState.applyJson(resource("http-body-business-event.json"));
    byte[] request =
        """
        {"operation":"EXCHANGE","sourceCurrency":"PEN","targetCurrency":"USD",
         "channel":"WEB","amount":2500}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] response =
        """
        {"status":"APPROVED","customerType":"STANDARD","rateType":"STANDARD",
         "receivedAmount":718.76}
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "application/json",
            "gzip",
            request,
            201,
            "application/json",
            "gzip",
            response,
            Map.of("x-client-segment", List.of("SALARY_ACCOUNT")),
            Map.of("x-rate-source", List.of("INTERNAL")),
            Map.of("campaign", List.of("JULY")),
            Context.root(),
            snapshot.generation()),
        "Servlet wrappers observe the logical body before a container compression stage");
    assertEquals(
        1,
        HttpBodyPolicyEngine.process(
            "INCOMING",
            "POST",
            "/api/exchanges",
            "application/json",
            "gzip",
            gzip(request),
            201,
            "application/json",
            "gzip",
            gzip(response),
            Map.of("x-client-segment", List.of("SALARY_ACCOUNT")),
            Map.of("x-rate-source", List.of("INTERNAL")),
            Map.of("campaign", List.of("JULY")),
            Context.root(),
            snapshot.generation()),
        "gzip bytes are decompressed with the same configured bound");

  }

  @Test
  void rejectsUnknownOrDuplicateCompiledPolicyVersions() {
    assertThrows(IllegalArgumentException.class, () -> HttpBodyPolicyEngine.parsePolicy("V|2\n"));
    assertThrows(
        IllegalArgumentException.class, () -> HttpBodyPolicyEngine.parsePolicy("V|1\nV|1\n"));
  }

  @Test
  void isolatesConcurrentGenerationSnapshotsAndBoundsTheParsedPolicyCache() throws Exception {
    String firstGeneration = "concurrent-a";
    String secondGeneration = "concurrent-b";
    String firstProperty =
        PolicyState.generationProperty(PolicyState.BODY_COMPILED_PROPERTY, firstGeneration);
    String secondProperty =
        PolicyState.generationProperty(PolicyState.BODY_COMPILED_PROPERTY, secondGeneration);
    System.setProperty(firstProperty, compiledPolicy("event-a", 1024));
    System.setProperty(secondProperty, compiledPolicy("event-b", 2048));

    ExecutorService workers = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> results = new ArrayList<>();
    try {
      for (int worker = 0; worker < 8; worker++) {
        String generation = worker % 2 == 0 ? firstGeneration : secondGeneration;
        int expected = worker % 2 == 0 ? 1024 : 2048;
        results.add(
            workers.submit(
                () -> {
                  start.await();
                  for (int iteration = 0; iteration < 5_000; iteration++) {
                    assertEquals(
                        expected,
                        HttpBodyPolicyEngine.captureLimit(
                            "OUTGOING", "POST", "/api/concurrent", generation));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> result : results) {
        result.get();
      }

      for (int index = 0; index < 20; index++) {
        String generation = "bounded-" + index;
        String property =
            PolicyState.generationProperty(PolicyState.BODY_COMPILED_PROPERTY, generation);
        System.setProperty(property, compiledPolicy("bounded-event-" + index, 1024 + index));
        assertEquals(
            1024 + index,
            HttpBodyPolicyEngine.captureLimit(
                "OUTGOING", "POST", "/api/concurrent", generation));
        System.clearProperty(property);
      }
      assertEquals(16, HttpBodyPolicyEngine.cachedPolicyCountForTest());
    } finally {
      start.countDown();
      workers.shutdownNow();
      System.clearProperty(firstProperty);
      System.clearProperty(secondProperty);
    }
  }

  private static int process(byte[] request, int status, byte[] response, String generation) {
    return HttpBodyPolicyEngine.process(
        "INCOMING",
        "POST",
        "/api/exchanges",
        "application/json; charset=UTF-8",
        "identity",
        request,
        status,
        "application/json",
        "identity",
        response,
        Map.of("x-client-segment", List.of("SALARY_ACCOUNT")),
        Map.of("x-rate-source", List.of("INTERNAL")),
        Map.of("campaign", List.of("JULY")),
        Context.root(),
        generation);
  }

  private static int process(
      byte[] request, int status, byte[] response, String generation, String statusValue) {
    String changed =
        new String(response, StandardCharsets.UTF_8).replace("APPROVED", statusValue);
    return process(request, status, changed.getBytes(StandardCharsets.UTF_8), generation);
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String compiledPolicy(String id, int maxBodyBytes) {
    return compiledPolicy(id, maxBodyBytes, "REQUEST_BODY");
  }

  private static String compiledPolicy(String id, int maxBodyBytes, String source) {
    return "V|1\n"
        + "E|"
        + encoded(id)
        + '|'
        + encoded("OUTGOING")
        + '|'
        + encoded("application/json")
        + '|'
        + encoded("application/json")
        + '|'
        + maxBodyBytes
        + '|'
        + encoded(id)
        + "|false|"
        + encoded("INFO")
        + "|\n"
        + "F|"
        + encoded(id)
        + '|'
        + source
        + '|'
        + encoded("amount")
        + '|'
        + encoded("test.amount")
        + "|DOUBLE|SPAN|RANGE||"
        + encoded("OTHER")
        + "|\n";
  }

  private static String httpMetadataEventPolicy() {
    return """
        {
          "schemaVersion": "1.4",
          "bodyEventPolicies": [{
            "id": "http-metadata-event",
            "ruleName": "HTTP metadata event",
            "direction": "INCOMING",
            "eventName": "http-metadata-event",
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/api/metadata"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["GET"]},
              {"source": "REQUEST_HEADER", "path": "X-Customer-Tier", "operator": "EQUALS", "values": ["PREMIUM"]},
              {"source": "REQUEST_QUERY", "path": "campaign", "operator": "EQUALS", "values": ["JULY 2026"]},
              {"source": "RESPONSE_HEADER", "path": "X-Approved-Amount", "operator": "IN", "values": ["1250.50", "2500.00"]}
            ],
            "fields": [
              {
                "source": "REQUEST_HEADER",
                "path": "X-Customer-Tier",
                "attribute": "customer.tier",
                "type": "STRING",
                "destinations": ["SPAN", "LOG", "METRIC"],
                "valuePolicy": {"type": "ENUM", "allowed": ["PREMIUM", "STANDARD"], "fallback": "OTHER"}
              },
              {
                "source": "RESPONSE_HEADER",
                "path": "X-Approved-Amount",
                "attribute": "approved.amount",
                "type": "DOUBLE",
                "destinations": ["SPAN", "LOG"]
              },
              {
                "source": "REQUEST_QUERY",
                "path": "campaign",
                "attribute": "request.campaign",
                "type": "STRING",
                "destinations": ["SPAN", "LOG", "METRIC"],
                "valuePolicy": {"type": "ENUM", "allowed": ["JULY 2026"], "fallback": "OTHER"}
              }
            ],
            "log": {"enabled": true, "severity": "INFO", "body": "HTTP metadata matched"}
          }],
          "eventMetricPolicies": [{
            "id": "http-metadata-amount",
            "eventName": "http-metadata-event",
            "name": "test.http.metadata.amount",
            "instrument": "HISTOGRAM",
            "unit": "1",
            "description": "Strict numeric response header",
            "valueField": "approved.amount",
            "dimensions": ["customer.tier", "request.campaign"],
            "buckets": [100, 1000, 5000]
          }]
        }
        """;
  }

  private static String pathParameterEventPolicy() {
    return """
        {
          "schemaVersion": "1.5",
          "bodyEventPolicies": [{
            "id": "account-transfers",
            "ruleName": "Account transfers",
            "direction": "INCOMING",
            "eventName": "account-transfers",
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/accounts/{accountId}/transfers"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["GET"]},
              {"source": "REQUEST_PATH_PARAM", "path": "accountId", "operator": "EQUALS", "values": ["AC-42"]}
            ],
            "fields": [{
              "source": "REQUEST_PATH_PARAM",
              "path": "accountId",
              "attribute": "business.account.id",
              "type": "STRING",
              "destinations": ["SPAN"]
            }]
          }]
        }
        """;
  }

  private static String attemptAndSuccessPolicy() {
    return """
        {
          "schemaVersion": "1.3",
          "bodyEventPolicies": [
            {
              "id": "exchange-attempt",
              "ruleName": "Exchange attempt",
              "direction": "INCOMING",
              "eventName": "exchange-attempt",
              "maxBodyBytes": 4096,
              "conditions": [
                {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/api/exchanges"]},
                {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["POST"]}
              ],
              "fields": [
                {
                  "source": "REQUEST_BODY",
                  "path": "amount",
                  "attribute": "exchange.amount",
                  "type": "DOUBLE",
                  "destinations": ["SPAN"]
                }
              ]
            },
            {
              "id": "exchange-success",
              "ruleName": "Exchange success",
              "direction": "INCOMING",
              "eventName": "exchange-success",
              "maxBodyBytes": 4096,
              "conditions": [
                {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/api/exchanges"]},
                {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["POST"]},
                {"source": "RESPONSE_STATUS", "operator": "EQUALS", "values": ["200"]}
              ],
              "fields": [
                {
                  "source": "REQUEST_BODY",
                  "path": "amount",
                  "attribute": "exchange.amount",
                  "type": "DOUBLE",
                  "destinations": ["SPAN"]
                }
              ]
            }
          ]
        }
        """;
  }

  private static String counterOnlyEventPolicy() {
    return """
        {
          "schemaVersion": "1.3",
          "bodyEventPolicies": [{
            "id": "exchange-count",
            "ruleName": "Completed exchanges",
            "direction": "INCOMING",
            "eventName": "exchange-completed",
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/api/exchanges"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["POST"]},
              {"source": "RESPONSE_STATUS", "operator": "IN", "values": ["200", "201"]}
            ],
            "fields": []
          }],
          "eventMetricPolicies": [{
            "id": "exchange-count-metric",
            "eventName": "exchange-completed",
            "name": "test.exchange.completed",
            "instrument": "COUNTER",
            "unit": "{operation}",
            "description": "Completed exchange operations",
            "valueField": "",
            "dimensions": [],
            "buckets": []
          }]
        }
        """;
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] gzip(byte[] body) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(body);
    }
    return output.toByteArray();
  }
}
