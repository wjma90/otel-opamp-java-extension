package dev.o11y.agent.messaging.jms;

import dev.o11y.agent.messaging.MessagingExchange;
import dev.o11y.agent.messaging.MessagingReflection;
import io.opentelemetry.context.Context;

/** Namespace-neutral bridge for both javax.jms and jakarta.jms providers. */
public final class JmsMessagingBridge {
  private static final ThreadLocal<Integer> PRODUCER_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<Integer> RECEIVE_DEPTH = ThreadLocal.withInitial(() -> 0);

  private JmsMessagingBridge() {}

  public static MessagingExchange.State producerEnter(Object producer, Object[] arguments) {
    int depth = PRODUCER_DEPTH.get();
    PRODUCER_DEPTH.set(depth + 1);
    if (depth > 0) {
      return null;
    }
    try {
      Object message = MessagingReflection.jmsMessageArgument(arguments);
      Object destination = MessagingReflection.jmsDestinationArgument(arguments, message);
      if (destination == null) {
        destination = MessagingReflection.invokeNoArgs(producer, "getDestination");
      }
      String destinationName = MessagingReflection.jmsDestination(message, destination);
      MessagingExchange.CaptureRequirements requirements =
          MessagingExchange.requirements("JMS_PRODUCER", destinationName);
      if (!requirements.active()) {
        return null;
      }
      Object conveniencePayload =
          message == null
              ? MessagingReflection.jmsConveniencePayload(arguments, destination)
              : null;
      MessagingReflection.MessageSnapshot snapshot =
          MessagingReflection.jmsMessage(
              message, destination, conveniencePayload, requirements);
      return MessagingExchange.capture(
          requirements,
          message == null ? arguments : message,
          snapshot.key(),
          snapshot.headers(),
          snapshot.properties(),
          snapshot.payload());
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static int producerExit(MessagingExchange.State state, Throwable error) {
    leaveProducer();
    return state == null ? 0 : state.complete(error);
  }

  public static ReceiveState consumerEnter() {
    int depth = RECEIVE_DEPTH.get();
    RECEIVE_DEPTH.set(depth + 1);
    return new ReceiveState(depth == 0, Context.current());
  }

  public static int consumerExit(ReceiveState state, Object message, Throwable error) {
    int depth = Math.max(0, RECEIVE_DEPTH.get() - 1);
    if (depth == 0) {
      RECEIVE_DEPTH.remove();
    } else {
      RECEIVE_DEPTH.set(depth);
    }
    if (state == null || !state.owner || error != null || message == null) {
      return 0;
    }
    io.opentelemetry.context.Scope scope = state.context.makeCurrent();
    try {
      MessagingExchange.State exchange = captureConsumer(message);
      return exchange == null ? 0 : exchange.complete(null);
    } catch (Throwable ignored) {
      return 0;
    } finally {
      scope.close();
    }
  }

  public static MessagingExchange.State listenerEnter(Object message) {
    return captureConsumer(message);
  }

  public static void listenerExit(MessagingExchange.State state, Throwable error) {
    if (state != null) {
      state.complete(error);
    }
  }

  private static MessagingExchange.State captureConsumer(Object message) {
    String destination = MessagingReflection.jmsDestination(message, null);
    MessagingExchange.CaptureRequirements requirements =
        MessagingExchange.requirements("JMS_CONSUMER", destination);
    if (!requirements.active()) {
      return null;
    }
    MessagingReflection.MessageSnapshot snapshot =
        MessagingReflection.jmsMessage(message, null, null, requirements);
    return MessagingExchange.capture(
        requirements,
        message,
        snapshot.key(),
        snapshot.headers(),
        snapshot.properties(),
        snapshot.payload());
  }

  private static void leaveProducer() {
    int depth = Math.max(0, PRODUCER_DEPTH.get() - 1);
    if (depth == 0) {
      PRODUCER_DEPTH.remove();
    } else {
      PRODUCER_DEPTH.set(depth);
    }
  }

  public static final class ReceiveState {
    private final boolean owner;
    private final Context context;

    private ReceiveState(boolean owner, Context context) {
      this.owner = owner;
      this.context = context;
    }
  }
}
