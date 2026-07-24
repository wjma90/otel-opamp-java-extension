package org.springframework.http;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Minimal Spring API test double; production code remains reflection-only. */
public interface HttpRequest {
  String getMethod();

  URI getURI();

  Map<String, List<String>> getHeaders();
}
