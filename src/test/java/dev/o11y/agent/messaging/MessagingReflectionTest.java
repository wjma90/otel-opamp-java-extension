package dev.o11y.agent.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingReflectionTest {
  @Test
  void extractsSupportedKafkaRecordValuesWithoutKafkaClasses() {
    FakeKafkaRecord record =
        new FakeKafkaRecord(
            "exchange.approved",
            "EX-42",
            "{\"amount\":150}",
            List.of(
                new FakeKafkaHeader("x-client-channel", "WEB".getBytes(StandardCharsets.UTF_8)),
                new FakeKafkaHeader("binary", new byte[] {(byte) 0xff})));

    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.kafkaRecord(
            record, List.of("x-client-channel", "binary"), true, true);

    assertEquals("exchange.approved", snapshot.destination());
    assertEquals("EX-42", snapshot.key());
    assertEquals(List.of("WEB"), snapshot.headers().get("x-client-channel"));
    assertNull(snapshot.headers().get("binary"));
    assertEquals("{\"amount\":150}", snapshot.payload());
  }

  @Test
  void extractsTextAndScalarPropertiesFromJmsMessage() {
    FakeJmsMessage message = new FakeJmsMessage();

    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.jmsMessage(
            message,
            null,
            null,
            List.of("jmscorrelationid"),
            List.of("channel"),
            true,
            true);

    assertEquals("exchange.approved", snapshot.destination());
    assertEquals("corr-7", snapshot.key());
    assertEquals(List.of("corr-7"), snapshot.headers().get("jmscorrelationid"));
    assertEquals("WEB", snapshot.properties().get("channel"));
    assertEquals("{\"amount\":250}", snapshot.payload());
  }

  @Test
  void acceptsOnlyStringOrByteArrayPayloads() {
    FakeKafkaRecord bytes =
        new FakeKafkaRecord("topic", null, new byte[] {1, 2}, List.of());
    FakeKafkaRecord unsupported =
        new FakeKafkaRecord("topic", null, Map.of("secret", "value"), List.of());

    assertArrayEquals(
        new byte[] {1, 2},
        (byte[]) MessagingReflection.kafkaRecord(bytes, List.of(), false, true).payload());
    assertNull(
        MessagingReflection.kafkaRecord(unsupported, List.of(), false, true).payload());
  }

  @Test
  void neverInvokesArbitraryKafkaKeyToString() {
    Object key = new Object() {
      @Override
      public String toString() {
        throw new AssertionError("application key toString must not run");
      }
    };

    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.kafkaRecord(
            new FakeKafkaRecord("topic", key, "{}", List.of()), List.of(), true, false);

    assertNull(snapshot.key());
  }

  @Test
  void readsOnlyRequestedKafkaHeadersAndDoesNotLoseLateSelector() {
    java.util.ArrayList<Object> headers = new java.util.ArrayList<>();
    UnrequestedKafkaHeader secret = new UnrequestedKafkaHeader();
    headers.add(secret);
    for (int index = 0; index < 100; index++) {
      headers.add(
          new FakeKafkaHeader(
              "unrelated-" + index, "ignored".getBytes(StandardCharsets.UTF_8)));
    }
    headers.add(
        new FakeKafkaHeader("X-Selected", "FOUND".getBytes(StandardCharsets.UTF_8)));

    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.kafkaRecord(
            new FakeKafkaRecord("topic", null, "{}", headers),
            List.of("x-selected"),
            false,
            false);

    assertEquals(Map.of("x-selected", List.of("FOUND")), snapshot.headers());
    assertFalse(secret.valueRead);
  }

  @Test
  void readsOnlyRequestedJmsPropertiesWithoutEnumeratingOthers() {
    FakeJmsMessage message = new FakeJmsMessage();

    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.jmsMessage(
            message,
            null,
            null,
            List.of(),
            List.of("channel"),
            false,
            false);

    assertEquals(Map.of("channel", "WEB"), snapshot.properties());
    assertEquals(List.of("channel"), message.requestedProperties);
  }

  @Test
  void supportsJmsProducerStringAndByteArrayConveniencePayloadsOnlyWhenRequested() {
    FakeDestination destination = new FakeDestination();
    Object[] arguments = {destination, "{\"status\":\"APPROVED\"}"};

    Object payload = MessagingReflection.jmsConveniencePayload(arguments, destination);
    MessagingReflection.MessageSnapshot captured =
        MessagingReflection.jmsMessage(
            null, destination, payload, List.of(), List.of(), false, true);
    MessagingReflection.MessageSnapshot omitted =
        MessagingReflection.jmsMessage(
            null, destination, payload, List.of(), List.of(), false, false);

    assertEquals("{\"status\":\"APPROVED\"}", captured.payload());
    assertNull(omitted.payload());
  }

  @Test
  void snapshotsDefensivelyCopyHeadersPropertiesAndBytePayloads() {
    byte[] payload = new byte[] {1, 2};
    java.util.ArrayList<String> values = new java.util.ArrayList<>(List.of("WEB"));
    java.util.LinkedHashMap<String, List<String>> headers = new java.util.LinkedHashMap<>();
    headers.put("channel", values);
    java.util.LinkedHashMap<String, String> properties = new java.util.LinkedHashMap<>();
    properties.put("tenant", "one");

    MessagingReflection.MessageSnapshot snapshot =
        new MessagingReflection.MessageSnapshot(
            "topic", null, headers, properties, payload);
    values.set(0, "MUTATED");
    headers.put("extra", List.of("value"));
    properties.put("tenant", "two");
    payload[0] = 9;

    assertEquals(List.of("WEB"), snapshot.headers().get("channel"));
    assertEquals("one", snapshot.properties().get("tenant"));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) snapshot.payload());
    byte[] returned = (byte[]) snapshot.payload();
    returned[0] = 8;
    assertArrayEquals(new byte[] {1, 2}, (byte[]) snapshot.payload());
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.headers().put("blocked", List.of("value")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.headers().get("channel").add("blocked"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.properties().put("blocked", "value"));
  }

  private record FakeKafkaHeader(String key, byte[] value) {}

  private record FakeKafkaRecord(String topic, Object key, Object value, Object headers) {}

  private static final class UnrequestedKafkaHeader {
    private boolean valueRead;

    public String key() {
      return "secret";
    }

    public byte[] value() {
      valueRead = true;
      throw new AssertionError("unrequested header value must not be read");
    }
  }

  private static final class FakeDestination {
    public String getTopicName() {
      return "exchange.approved";
    }
  }

  private static final class FakeJmsMessage {
    private final java.util.ArrayList<String> requestedProperties = new java.util.ArrayList<>();

    public Object getJMSDestination() {
      return new FakeDestination();
    }

    public String getJMSCorrelationID() {
      return "corr-7";
    }

    public String getJMSMessageID() {
      return "id-1";
    }

    public String getJMSType() {
      return "exchange";
    }

    public boolean getJMSRedelivered() {
      return false;
    }

    public int getJMSPriority() {
      return 4;
    }

    public int getJMSDeliveryMode() {
      return 2;
    }

    public long getJMSExpiration() {
      return 0;
    }

    public long getJMSTimestamp() {
      return 1;
    }

    public String getText() {
      return "{\"amount\":250}";
    }

    public java.util.Enumeration<String> getPropertyNames() {
      throw new AssertionError("JMS properties must be read only by compiled selector");
    }

    public Object getObjectProperty(String name) {
      requestedProperties.add(name);
      return "channel".equals(name) ? "WEB" : null;
    }
  }
}
