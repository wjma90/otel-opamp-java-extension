package dev.o11y.agent.http.client.spring;

import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesBuilder;
import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer;

/** Allows only the Spring Web types targeted by the RestClient policy instrumentation. */
public final class SpringRestClientIgnoredTypesConfigurer implements IgnoredTypesConfigurer {
  @Override
  public void configure(IgnoredTypesBuilder builder) {
    builder
        .allowClass("org.springframework.web.client.RestClient")
        .allowClass("org.springframework.web.client.DefaultRestClientBuilder")
        .allowClass("org.springframework.web.client.DefaultRestClient");
  }
}
