package dev.o11y.quarkus.http;

import dev.o11y.quarkus.method.ExchangeCalculator;
import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public final class ExchangeResource {
  @Inject ExchangeCalculator calculator;

  @POST
  @Path("api/exchanges/{accountId}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response exchange(
      @PathParam("accountId") String accountId,
      @QueryParam("campaign") String campaign,
      ExchangeRequest request) {
    ExchangeResponse response =
        new ExchangeResponse(
            "APPROVED",
            accountId,
            campaign,
            request.channel(),
            request.amount(),
            calculator.convert(request.amount(), 3.4782),
            request.marker());
    return Response.status(Response.Status.CREATED)
        .header("X-Rate-Type", "QUARKUS_LTS")
        .entity(response)
        .build();
  }

  @GET
  @Path("healthz")
  public Response health() {
    return Response.noContent().build();
  }

  @POST
  @Path("__stop")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response stop() {
    Quarkus.asyncExit(0);
    return Response.accepted().build();
  }

  @GET
  @Path("__o11y-state")
  @Produces(MediaType.TEXT_PLAIN)
  public String o11yState() {
    return System.getProperty("o11y.quarkus.rest.active-exchanges", "unavailable");
  }

  public record ExchangeRequest(String channel, double amount, String marker) {}

  public record ExchangeResponse(
      String status,
      String accountId,
      String campaign,
      String channel,
      double sourceAmount,
      double targetAmount,
      String marker) {}
}
