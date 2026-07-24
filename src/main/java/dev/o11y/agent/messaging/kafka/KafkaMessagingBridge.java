package dev.o11y.agent.messaging.kafka;

import dev.o11y.agent.messaging.MessagingExchange;
import dev.o11y.agent.messaging.MessagingReflection;

/** Reflection-only Kafka producer/consumer bridge injected into the application class loader. */
public final class KafkaMessagingBridge {
  private static final int MAX_RECORDS_PER_POLL = 1000;
  private static final ThreadLocal<Integer> POLL_DEPTH = ThreadLocal.withInitial(() -> 0);

  private KafkaMessagingBridge() {}

  public static MessagingExchange.State producerEnter(Object record) {
    try {
      String destination = MessagingReflection.kafkaDestination(record);
      MessagingExchange.CaptureRequirements requirements =
          MessagingExchange.requirements("KAFKA_PRODUCER", destination);
      if (!requirements.active()) {
        return null;
      }
      MessagingReflection.MessageSnapshot snapshot =
          MessagingReflection.kafkaRecord(record, requirements);
      return MessagingExchange.capture(
          requirements,
          record,
          snapshot.key(),
          snapshot.headers(),
          snapshot.properties(),
          snapshot.payload());
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static void producerExit(MessagingExchange.State state, Throwable error) {
    if (state != null) {
      state.complete(error);
    }
  }

  public static PollState consumerEnter() {
    int depth = POLL_DEPTH.get();
    POLL_DEPTH.set(depth + 1);
    return new PollState(depth == 0, io.opentelemetry.context.Context.current());
  }

  public static int consumerExit(PollState state, Object records, Throwable error) {
    int depth = Math.max(0, POLL_DEPTH.get() - 1);
    if (depth == 0) {
      POLL_DEPTH.remove();
    } else {
      POLL_DEPTH.set(depth);
    }
    if (state == null
        || !state.owner
        || error != null
        || !(records instanceof Iterable<?> iterable)) {
      return 0;
    }
    int emitted = 0;
    int inspected = 0;
    io.opentelemetry.context.Scope scope = state.context.makeCurrent();
    try {
      for (Object record : iterable) {
        if (inspected++ >= MAX_RECORDS_PER_POLL) {
          break;
        }
        String destination = MessagingReflection.kafkaDestination(record);
        MessagingExchange.CaptureRequirements requirements =
            MessagingExchange.requirements("KAFKA_CONSUMER", destination);
        if (!requirements.active()) {
          continue;
        }
        MessagingReflection.MessageSnapshot snapshot =
            MessagingReflection.kafkaRecord(record, requirements);
        MessagingExchange.State exchange =
            MessagingExchange.capture(
                requirements,
                record,
                snapshot.key(),
                snapshot.headers(),
                snapshot.properties(),
                snapshot.payload());
        emitted += exchange.complete(null);
      }
    } catch (Throwable ignored) {
      // Telemetry must never alter the consumer result.
    } finally {
      scope.close();
    }
    return emitted;
  }

  public static final class PollState {
    private final boolean owner;
    private final io.opentelemetry.context.Context context;

    private PollState(boolean owner, io.opentelemetry.context.Context context) {
      this.owner = owner;
      this.context = context;
    }
  }
}
