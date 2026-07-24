package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathParameterPolicyTest {
  @Test
  void acceptsSelectorDeclaredByTheRequestPathTemplate() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(pathParameterPolicy("accountId"));

    List<String> errors = PolicyValidator.validate(policy);

    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void rejectsFieldSelectorMissingFromEveryRequestPathTemplate() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(pathParameterPolicy("customerId"));

    List<String> errors = PolicyValidator.validate(policy);

    assertTrue(
        errors.stream()
            .anyMatch(
                error ->
                    error.contains(
                        "REQUEST_PATH_PARAM selector customerId must appear as {customerId}")),
        errors.toString());
  }

  @Test
  void rejectsConditionSelectorMissingFromEveryRequestPathTemplate() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            pathParameterPolicy("accountId")
                .replace(
                    """
                    {"source": "RESPONSE_STATUS", "operator": "EQUALS", "values": ["200"]}
                    """.trim(),
                    """
                    {"source": "REQUEST_PATH_PARAM", "path": "customerId", "operator": "EQUALS", "values": ["C-7"]}
                    """.trim()));

    List<String> errors = PolicyValidator.validate(policy);

    assertTrue(
        errors.stream()
            .anyMatch(
                error ->
                    error.contains(
                        "REQUEST_PATH_PARAM selector customerId must appear as {customerId}")),
        errors.toString());
  }

  @Test
  void rejectsNamedPathParametersForOutgoingHttp() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.5",
              "bodyEventPolicies": [{
                "id": "unsupported-outgoing-path-variable",
                "ruleName": "Unsupported outgoing path variable",
                "direction": "OUTGOING",
                "eventName": "unsupported-outgoing-path-variable",
                "conditions": [
                  {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/accounts/{accountId}"]},
                  {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["GET"]}
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
            """);

    assertTrue(
        PolicyValidator.validate(policy).stream()
            .anyMatch(error -> error.contains("supported only for INCOMING HTTP")));
  }

  private static String pathParameterPolicy(String selector) {
    return """
        {
          "schemaVersion": "1.5",
          "bodyEventPolicies": [{
            "id": "incoming-path-variable",
            "ruleName": "Incoming path variable",
            "direction": "INCOMING",
            "eventName": "incoming-path-variable",
            "conditions": [
              {"source": "REQUEST_PATH", "operator": "EQUALS", "values": ["/accounts/{accountId}"]},
              {"source": "REQUEST_METHOD", "operator": "EQUALS", "values": ["GET"]},
              {"source": "RESPONSE_STATUS", "operator": "EQUALS", "values": ["200"]}
            ],
            "fields": [{
              "source": "REQUEST_PATH_PARAM",
              "path": "%s",
              "attribute": "business.account.id",
              "type": "STRING",
              "destinations": ["SPAN"]
            }]
          }]
        }
        """.formatted(selector);
  }
}
