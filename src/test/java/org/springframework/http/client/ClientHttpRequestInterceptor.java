package org.springframework.http.client;

import java.io.IOException;
import org.springframework.http.HttpRequest;

/** Minimal Spring API test double; production code remains reflection-only. */
public interface ClientHttpRequestInterceptor {
  ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException;

  /** Spring Framework 7 composition contract. */
  default ClientHttpRequestExecution apply(ClientHttpRequestExecution execution) {
    return (request, body) -> intercept(request, body, execution);
  }
}
