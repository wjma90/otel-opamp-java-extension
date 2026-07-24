package org.springframework.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Minimal Spring API test double; production code remains reflection-only. */
public interface ClientHttpResponse extends AutoCloseable {
  int getStatusCode();

  Map<String, List<String>> getHeaders();

  InputStream getBody() throws IOException;

  @Override
  void close();
}
