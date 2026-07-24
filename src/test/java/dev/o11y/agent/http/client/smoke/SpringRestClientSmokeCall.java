package dev.o11y.agent.http.client.smoke;

import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Spring-only fixture deliberately loaded after Java Agent premain installs its transformer. */
final class SpringRestClientSmokeCall {
  private SpringRestClientSmokeCall() {}

  static void invoke(String endpoint, String library, double amount) {
    ResponseEntity<String> response =
        RestClient.builder()
            .build()
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-O11y-Smoke-Client", library)
            .body(requestJson(library, amount))
            .retrieve()
            .toEntity(String.class);
    require(response.getStatusCode().value() == 201, "Spring response status changed");
    require(
        ("approved-" + library).equals(
            response.getHeaders().getFirst("X-O11y-Smoke-Result")),
        "Spring response header was not preserved");
    String body = response.getBody();
    require(body != null && body.contains("\"status\":\"APPROVED\""),
        "Spring response JSON was not preserved");
    require(
        body.contains("\"acceptedAmount\":" + number(amount / 2)),
        "Spring response amount was not preserved");
  }

  private static String requestJson(String library, double amount) {
    return "{\"operation\":\"OUTGOING_SMOKE\",\"library\":\""
        + library
        + "\",\"amount\":"
        + number(amount)
        + '}';
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
