package dev.o11y.agent.http.client.smoke;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/** Real WebClient call through the default Reactor Netty connector. */
final class SpringWebClientSmokeCall {
  private SpringWebClientSmokeCall() {}

  static void invoke(String endpoint, String library, double amount) {
    WebClient client = WebClient.builder().build();
    Result response =
        client
            .post()
            .uri(endpoint)
            .header("X-O11y-Smoke-Client", library)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(OutgoingClientsSmokeApplication.requestJson(library, amount))
            .exchangeToMono(
                value ->
                    value
                        .bodyToMono(String.class)
                        .map(
                            body ->
                                new Result(
                                    value.statusCode().value(),
                                    value
                                        .headers()
                                        .header("X-O11y-Smoke-Result")
                                        .stream()
                                        .findFirst()
                                        .orElse(null),
                                    body)))
            .block(Duration.ofSeconds(8));
    OutgoingClientsSmokeApplication.require(response != null, "WebClient response disappeared");
    OutgoingClientsSmokeApplication.verifyResponse(
        library,
        amount,
        response.resultHeader(),
        response.body());
    OutgoingClientsSmokeApplication.require(
        response.status() == 201, "WebClient response status changed");
  }

  private record Result(int status, String resultHeader, String body) {}
}
