package dev.o11y.agent.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReflectiveHttpAccessTest {
  @Test
  void boundsUntrustedHeaderCollectionsBeforeRetainingThem() {
    Map<String, List<Object>> source = new LinkedHashMap<>();
    source.put("x-hostile", List.of(new HostileValue()));
    for (int header = 0; header < 160; header++) {
      ArrayList<Object> values = new ArrayList<>();
      for (int value = 0; value < 20; value++) {
        values.add("x".repeat(5000));
      }
      source.put("x-header-" + header, values);
    }

    Map<String, List<String>> captured =
        ReflectiveHttpAccess.headers(new HeaderCarrier(source));

    assertEquals(128, captured.size());
    assertFalse(captured.containsKey("x-hostile"));
    captured
        .values()
        .forEach(
            values -> {
              assertEquals(16, values.size());
              values.forEach(value -> assertEquals(4096, value.length()));
            });
  }

  @Test
  void readsApacheFiveHeaderArraysReturnedByGetHeaders() {
    Map<String, List<String>> captured =
        ReflectiveHttpAccess.headers(
            new ApacheFiveHeaderCarrier(
                new ApacheHeader[] {
                  new ApacheHeader("Content-Type", "application/json"),
                  new ApacheHeader("X-Result", "APPROVED")
                }));

    assertEquals(List.of("application/json"), captured.get("Content-Type"));
    assertEquals(List.of("APPROVED"), captured.get("X-Result"));
  }

  public record HeaderCarrier(Map<String, List<Object>> headers) {
    public Map<String, List<Object>> getHeaders() {
      return headers;
    }
  }

  public record ApacheFiveHeaderCarrier(ApacheHeader[] headers) {
    public ApacheHeader[] getHeaders() {
      return headers;
    }
  }

  public record ApacheHeader(String name, String value) {
    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }
  }

  private static final class HostileValue {
    @Override
    public String toString() {
      throw new IllegalStateException("must not be called");
    }
  }
}
