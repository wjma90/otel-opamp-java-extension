package dev.o11y.agent.http.client.smoke;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;

/** Real classic and Simple async Apache HttpClient 5 calls for the agent smoke. */
final class Apache5ClientSmokeCall {
  private Apache5ClientSmokeCall() {}

  static void invokeClassic(String endpoint, String library, double amount) throws Exception {
    try (CloseableHttpClient client = HttpClients.createDefault()) {
      HttpPost request = new HttpPost(endpoint);
      request.setHeader("X-O11y-Smoke-Client", library);
      request.setEntity(
          new ByteArrayEntity(
              OutgoingClientsSmokeApplication.requestJson(library, amount)
                  .getBytes(StandardCharsets.UTF_8),
              ContentType.APPLICATION_JSON));
      client.execute(
          request,
          response -> {
            String result =
                response.getFirstHeader("X-O11y-Smoke-Result") == null
                    ? null
                    : response.getFirstHeader("X-O11y-Smoke-Result").getValue();
            OutgoingClientsSmokeApplication.require(
                response.getCode() == 201, library + " response status changed");
            OutgoingClientsSmokeApplication.verifyResponse(
                library,
                amount,
                result,
                EntityUtils.toString(response.getEntity()));
            return null;
          });
    }
  }

  static void invokeAsync(String endpoint, String library, double amount) throws Exception {
    try (CloseableHttpAsyncClient client = HttpAsyncClients.createDefault()) {
      client.start();
      SimpleHttpRequest request = SimpleHttpRequest.create("POST", endpoint);
      request.setHeader("X-O11y-Smoke-Client", library);
      request.setBody(
          OutgoingClientsSmokeApplication.requestJson(library, amount),
          ContentType.APPLICATION_JSON);
      SimpleHttpResponse response =
          client.execute(request, null).get(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS);
      OutgoingClientsSmokeApplication.require(
          response.getCode() == 201, library + " response status changed");
      OutgoingClientsSmokeApplication.verifyResponse(
          library,
          amount,
          response.getFirstHeader("X-O11y-Smoke-Result") == null
              ? null
              : response.getFirstHeader("X-O11y-Smoke-Result").getValue(),
          response.getBodyText());
    }
  }
}
