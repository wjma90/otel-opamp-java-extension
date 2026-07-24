package dev.o11y.quarkus.messaging;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.jms.JMSException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

/** HTTP trigger used only by the Quarkus JVM messaging smoke test. */
@Path("/smoke/messaging")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@IfBuildProperty(name = "o11y.messaging.enabled", stringValue = "true")
public final class MessagingResource {
  @Inject KafkaExchangeFlow kafka;
  @Inject JmsExchangeFlow jms;

  @POST
  @Path("/kafka")
  public CompletionStage<Response> kafka(MessagingRequest request) {
    String payload = payload(request);
    return kafka.publish(payload).thenApply(ignored -> Response.accepted(payload).build());
  }

  @GET
  @Path("/kafka/last")
  public Response lastKafka() {
    String payload = kafka.lastConsumed();
    return payload == null
        ? Response.status(Response.Status.NOT_FOUND).build()
        : Response.ok(payload).build();
  }

  @POST
  @Path("/jms")
  @Blocking
  public Response jms(MessagingRequest request) throws JMSException {
    return Response.ok(jms.roundTrip(payload(request))).build();
  }

  private static String payload(MessagingRequest request) {
    if (request == null
        || request.channel() == null
        || request.channel().isBlank()
        || !Double.isFinite(request.amount())
        || request.amount() <= 0) {
      throw new IllegalArgumentException("channel and a positive finite amount are required");
    }
    return String.format(
        Locale.ROOT,
        "{\"status\":\"APPROVED\",\"channel\":\"%s\",\"amount\":%.2f}",
        request.channel().replace("\"", ""),
        request.amount());
  }

  public record MessagingRequest(String channel, double amount) {}
}
