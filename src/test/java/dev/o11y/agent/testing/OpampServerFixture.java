package dev.o11y.agent.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okio.ByteString;
import opamp.proto.AgentConfigFile;
import opamp.proto.AgentConfigMap;
import opamp.proto.AgentRemoteConfig;
import opamp.proto.AgentToServer;
import opamp.proto.RemoteConfigStatuses;
import opamp.proto.ServerCapabilities;
import opamp.proto.ServerToAgent;

/**
 * Minimal real OpAMP HTTP server for Java Agent integration tests.
 *
 * <p>The fixture exchanges the actual protobuf messages used in production. It is intentionally
 * smaller than a Control Plane: tests own the remote config and assert the client's acknowledgement
 * without introducing another product as a test dependency.
 */
public final class OpampServerFixture implements AutoCloseable {
  private static final String CONTENT_TYPE = "application/x-protobuf";
  private static final String CONFIG_NAME = "dev.o11y/http-headers.json";

  private final HttpServer server;
  private final ExecutorService executor;
  private final AgentRemoteConfig remoteConfig;
  private final String expectedService;
  private final CompletableFuture<Void> applied = new CompletableFuture<>();
  private final AtomicBoolean observedDescription = new AtomicBoolean();
  private final AtomicInteger requestCount = new AtomicInteger();

  public OpampServerFixture(String expectedService, String policySet) throws Exception {
    this.expectedService = expectedService;
    byte[] policyBytes = policySet.getBytes(StandardCharsets.UTF_8);
    ByteString configHash =
        ByteString.of(MessageDigest.getInstance("SHA-256").digest(policyBytes));
    AgentConfigFile configFile =
        new AgentConfigFile(ByteString.of(policyBytes), "application/json");
    AgentConfigMap configMap =
        new AgentConfigMap(Map.of(CONFIG_NAME, configFile));
    remoteConfig = new AgentRemoteConfig(configMap, configHash);

    server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext("/v1/opamp", this::handle);
    server.start();
  }

  public String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/opamp";
  }

  public void awaitApplied(Duration timeout) throws Exception {
    applied.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public int requestCount() {
    return requestCount.get();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      require("POST".equals(exchange.getRequestMethod()), "OpAMP client did not use POST");
      require(
          header(exchange, "Content-Type").startsWith(CONTENT_TYPE),
          "OpAMP client did not send protobuf");
      require(
          expectedService.equals(header(exchange, "X-Service-Name")),
          "OpAMP service header changed");
      require(
          "http-poll".equals(header(exchange, "X-O11y-Transport")),
          "OpAMP transport header changed");
      require(
          "2".equals(header(exchange, "X-O11y-Poll-Interval-Seconds")),
          "OpAMP poll interval header changed");

      AgentToServer request = AgentToServer.ADAPTER.decode(exchange.getRequestBody());
      require(request.instance_uid.size() == 16, "OpAMP instance UID must contain 16 bytes");
      if (requestCount.get() == 0) {
        require(request.agent_description != null, "initial OpAMP agent description is missing");
        observedDescription.set(true);
      }
      require(observedDescription.get(), "OpAMP agent description was never reported");
      requestCount.incrementAndGet();

      if (request.remote_config_status != null) {
        require(
            request.remote_config_status.status
                != RemoteConfigStatuses.RemoteConfigStatuses_FAILED,
            "Java Agent rejected remote config: "
                + request.remote_config_status.error_message);
        if (request.remote_config_status.status
            == RemoteConfigStatuses.RemoteConfigStatuses_APPLIED) {
          require(
              remoteConfig.config_hash.equals(
                  request.remote_config_status.last_remote_config_hash),
              "Java Agent acknowledged a different remote config hash");
          applied.complete(null);
        }
      }

      long capabilities =
          ServerCapabilities.ServerCapabilities_AcceptsStatus.getValue()
              | ServerCapabilities.ServerCapabilities_OffersRemoteConfig.getValue();
      ServerToAgent response =
          new ServerToAgent.Builder()
              .instance_uid(request.instance_uid)
              .capabilities(capabilities)
              .remote_config(remoteConfig)
              .build();
      byte[] body = response.encode();
      exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } catch (Throwable failure) {
      applied.completeExceptionally(failure);
      byte[] body = failure.getMessage().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, body.length);
      exchange.getResponseBody().write(body);
    } finally {
      exchange.close();
    }
  }

  private static String header(HttpExchange exchange, String name) {
    return exchange.getRequestHeaders().getFirst(name) == null
        ? ""
        : exchange.getRequestHeaders().getFirst(name);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      executor.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
