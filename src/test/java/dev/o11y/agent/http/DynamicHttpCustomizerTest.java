package dev.o11y.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.o11y.agent.policy.DynamicPolicy;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynamicHttpCustomizerTest {
  @Test
  void transformsHeadersIntoBoundedDimensions() {
    DynamicPolicy.ValuePolicy enumeration = new DynamicPolicy.ValuePolicy();
    enumeration.type = "ENUM";
    enumeration.allowed = List.of("WEB", "API");
    enumeration.fallback = "OTHER";

    DynamicPolicy.ValuePolicy ranges = new DynamicPolicy.ValuePolicy();
    ranges.type = "RANGE";
    ranges.fallback = "OTHER";
    ranges.ranges =
        List.of(range(1000d, "0-1000"), range(3000d, "1000-3000"), range(null, "3000+"));

    DynamicPolicy.ValuePolicy bool = new DynamicPolicy.ValuePolicy();
    bool.type = "BOOLEAN";
    bool.fallback = "false";

    assertEquals("WEB", DynamicHttpCustomizer.transform("web", enumeration));
    assertEquals("OTHER", DynamicHttpCustomizer.transform("MOBILE", enumeration));
    assertEquals("1000-3000", DynamicHttpCustomizer.transform("2500", ranges));
    assertEquals("OTHER", DynamicHttpCustomizer.transform("not-a-number", ranges));
    assertEquals(false, DynamicHttpCustomizer.transform(null, bool));
    assertEquals(true, DynamicHttpCustomizer.transform("true", bool));
  }

  @Test
  void canonicalizesAndCapturesCustomMetricHeaderWithOuterWhitespace() throws Exception {
    DynamicPolicy parsed =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "metricPolicies": [{
                "id": "http-channel",
                "customAttributes": [{
                  "source": "REQUEST_HEADER",
                  "header": " X-Client-Channel ",
                  "attribute": "client.channel",
                  "destinations": ["SPAN"]
                }]
              }]
            }
            """);

    assertEquals(
        "x-client-channel",
        parsed.metricPolicies().getFirst().customAttributes.getFirst().header);

    DynamicPolicy.AttributeSource customAttribute =
        parsed.metricPolicies().getFirst().customAttributes.getFirst();
    customAttribute.header = " X-Client-Channel ";
    Map<String, String> captured =
        DynamicHttpCustomizer.HeaderContextCustomizer.captureHeaders(
            new HeaderRequest(), parsed);

    assertEquals(Map.of("x-client-channel", "WEB"), captured);
  }

  @Test
  void scopesHttpMetricHeadersToTheConfiguredDirection() throws Exception {
    DynamicPolicy parsed =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "metricPolicies": [{
                "id": "outgoing-channel",
                "direction": "OUTGOING",
                "customAttributes": [{
                  "source": "REQUEST_HEADER",
                  "header": "x-client-channel",
                  "attribute": "client.channel",
                  "destinations": ["SPAN"]
                }]
              }]
            }
            """);

    assertEquals(
        Map.of(),
        DynamicHttpCustomizer.HeaderContextCustomizer.captureHeaders(
            new ClientHeaderRequest(), parsed, "INCOMING"));
    assertEquals(
        Map.of("x-client-channel", "API"),
        DynamicHttpCustomizer.HeaderContextCustomizer.captureHeaders(
            new ClientHeaderRequest(), parsed, "OUTGOING"));
  }

  @Test
  void serverCompletionCopiesTypedAttributesBeforeTheInstrumenterEndsTheSpan() {
    Context context = HttpServerCompletionBridge.install(Context.root());
    assertEquals(
        true,
        HttpServerCompletionBridge.arm(
            context,
            (ignored, failure) ->
                Map.of(
                    "business.approved", true,
                    "business.amount", 2500d,
                    "http.response.header.x_rate_type", List.of("PREFERRED"))));

    AttributesBuilder builder = Attributes.builder();
    new DynamicHttpCustomizer.HttpServerCompletionExtractor()
        .onEnd(builder, context, new Object(), new Object(), null);
    Attributes attributes = builder.build();

    assertEquals(true, attributes.get(AttributeKey.booleanKey("business.approved")));
    assertEquals(2500d, attributes.get(AttributeKey.doubleKey("business.amount")));
    assertEquals(
        List.of("PREFERRED"),
        attributes.get(AttributeKey.stringArrayKey("http.response.header.x_rate_type")));

    AttributesBuilder second = Attributes.builder();
    new DynamicHttpCustomizer.HttpServerCompletionExtractor()
        .onEnd(second, context, new Object(), new Object(), null);
    assertEquals(Attributes.empty(), second.build(), "completion must be consumed exactly once");
  }

  private static DynamicPolicy.Range range(Double max, String label) {
    DynamicPolicy.Range range = new DynamicPolicy.Range();
    range.max = max;
    range.label = label;
    return range;
  }

  private static final class HeaderRequest {
    public String getHeader(String name) {
      return "x-client-channel".equals(name) ? "WEB" : null;
    }
  }

  private static final class ClientHeaderRequest {
    public String header(String name) {
      return "x-client-channel".equals(name) ? "API" : null;
    }
  }
}
