package dev.o11y.agent.http.client.smoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Real synchronous and asynchronous JDK HttpClient calls for the isolated agent smoke. */
final class JdkHttpClientSmokeCall {
  private JdkHttpClientSmokeCall() {}

  static void invokeSync(String endpoint, String library, double amount) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response =
          client.send(request(endpoint, library, amount), HttpResponse.BodyHandlers.ofString());
      verify(library, amount, response);
    }
  }

  static void invokeAsync(String endpoint, String library, double amount) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response =
          client
              .sendAsync(request(endpoint, library, amount), HttpResponse.BodyHandlers.ofString())
              .get(Duration.ofSeconds(8).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      verify(library, amount, response);
    }
  }

  private static HttpRequest request(String endpoint, String library, double amount) {
    return HttpRequest.newBuilder(URI.create(endpoint))
        .header("Content-Type", "application/json")
        .header("X-O11y-Smoke-Client", library)
        .POST(
            HttpRequest.BodyPublishers.ofString(
                OutgoingClientsSmokeApplication.requestJson(library, amount),
                StandardCharsets.UTF_8))
        .build();
  }

  private static void verify(
      String library, double amount, HttpResponse<String> response) {
    OutgoingClientsSmokeApplication.require(
        response.statusCode() == 201, library + " response status changed");
    OutgoingClientsSmokeApplication.verifyResponse(
        library,
        amount,
        response.headers().firstValue("X-O11y-Smoke-Result").orElse(null),
        response.body());
  }
}
