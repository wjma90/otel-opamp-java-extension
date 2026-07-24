package org.springframework.web.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/** Minimal Spring API test double with the public builder contract used by the bridge. */
public final class DefaultRestClientBuilder {
  private final List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();

  public DefaultRestClientBuilder requestInterceptor(ClientHttpRequestInterceptor interceptor) {
    interceptors.add(interceptor);
    return this;
  }

  public DefaultRestClientBuilder requestInterceptors(
      Consumer<List<ClientHttpRequestInterceptor>> consumer) {
    consumer.accept(interceptors);
    return this;
  }

  public Object build() {
    return new Object();
  }

  public List<ClientHttpRequestInterceptor> interceptors() {
    return List.copyOf(interceptors);
  }
}
