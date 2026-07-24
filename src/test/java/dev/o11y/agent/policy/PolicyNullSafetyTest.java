package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyNullSafetyTest {
  private static final List<String> METHOD_PACKAGES = List.of("dev.o11y.rates.service");

  @Test
  void rejectsExplicitNullMethodObjectsWithoutThrowingNullPointerException() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.5",
              "methodPolicies": [{
                "id": "method-null-v1",
                "packagePrefix": "dev.o11y.rates.service",
                "className": "dev.o11y.rates.service.ExchangeRateCalculator",
                "methodName": "calculate",
                "captures": [{
                  "source": "ARGUMENT",
                  "argumentIndex": 0,
                  "attribute": "customer.type",
                  "type": "STRING",
                  "destinations": ["METRIC"],
                  "valuePolicy": null
                }],
                "metrics": [{
                  "name": "test.null.method",
                  "instrument": "COUNTER",
                  "unit": "1",
                  "value": null,
                  "buckets": []
                }],
                "log": null
              }]
            }
            """);

    List<String> errors = PolicyValidator.validate(policy, METHOD_PACKAGES);

    assertContains(errors, "bounded value policy is required");
    assertContains(errors, "value is required");
    assertContains(errors, "log must be an object");
  }

  @Test
  void rejectsExplicitNullHttpEventObjectsWithoutThrowingNullPointerException()
      throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.5",
              "bodyEventPolicies": [{
                "id": "http-null-v1",
                "ruleName": "HTTP null safety",
                "direction": "INCOMING",
                "conditions": [
                  {
                    "source": "REQUEST_PATH",
                    "operator": "EQUALS",
                    "values": ["/api/exchanges/{exchangeId}"]
                  },
                  {
                    "source": "REQUEST_METHOD",
                    "operator": "EQUALS",
                    "values": ["POST"]
                  }
                ],
                "eventName": "http-null-safety",
                "fields": [{
                  "attribute": "exchange.id",
                  "source": "REQUEST_PATH_PARAM",
                  "path": "exchangeId",
                  "type": "STRING",
                  "destinations": ["SPAN"],
                  "valuePolicy": null
                }],
                "log": null
              }]
            }
            """);

    List<String> errors = PolicyValidator.validate(policy, METHOD_PACKAGES);

    assertContains(errors, "valuePolicy must be an object");
    assertContains(errors, "log must be an object");
  }

  @Test
  void rejectsExplicitNullMessagingObjectsWithoutThrowingNullPointerException()
      throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.5",
              "messagingEventPolicies": [{
                "id": "kafka-null-v1",
                "ruleName": "Kafka null safety",
                "scope": "KAFKA_PRODUCER",
                "conditions": [{
                  "source": "DESTINATION",
                  "operator": "EQUALS",
                  "values": ["exchange.completed"]
                }],
                "eventName": "kafka-null-safety",
                "fields": [{
                  "attribute": "exchange.channel",
                  "source": "PAYLOAD",
                  "path": "channel",
                  "type": "STRING",
                  "destinations": ["SPAN"],
                  "valuePolicy": null
                }],
                "log": null
              }]
            }
            """);

    List<String> errors = PolicyValidator.validate(policy, METHOD_PACKAGES);

    assertContains(errors, "valuePolicy must be an object");
    assertContains(errors, "log must be an object");
  }

  @Test
  void replacesNullListEntriesWithInvalidPlaceholdersForDeterministicValidation()
      throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.5",
              "requestHeaders": [null],
              "methodPolicies": [null],
              "bodyEventPolicies": [null],
              "messagingEventPolicies": [null]
            }
            """);

    List<String> errors = PolicyValidator.validate(policy, METHOD_PACKAGES);

    assertContains(errors, "requestHeaders: contains an invalid or duplicated header");
    assertContains(errors, "method policies require unique IDs");
    assertContains(errors, "body event policies require unique IDs");
    assertContains(errors, "messaging event policies require unique IDs");
  }

  private static void assertContains(List<String> errors, String expected) {
    assertTrue(
        errors.stream().anyMatch(error -> error.contains(expected)),
        () -> "Expected error containing '" + expected + "' but got " + errors);
  }
}
