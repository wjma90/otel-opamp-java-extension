package org.springframework.http.client;

import java.io.IOException;
import org.springframework.http.HttpRequest;

/** Minimal Spring API test double; production code remains reflection-only. */
public interface ClientHttpRequestExecution {
  ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException;
}
