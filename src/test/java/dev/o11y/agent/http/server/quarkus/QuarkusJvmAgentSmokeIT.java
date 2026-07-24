package dev.o11y.agent.http.server.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.o11y.agent.policy.QuarkusPolicyFixtureCompiler;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class QuarkusJvmAgentSmokeIT {
  private static final String GENERATION = "quarkus-smoke";

  @Test
  void fastJarCapturesQuarkusRestOrClassicServletExactlyOnce() throws Exception {
    Path runner = optionalFixture();
    Path agent = requiredFile("opentelemetry.javaagent.jar");
    Path extension = requiredFile("packaged.extension.jar");
    QuarkusPolicyFixtureCompiler.Compiled policy =
        QuarkusPolicyFixtureCompiler.compile(resource("quarkus-jvm-http-method.json"));
    int port = freePort();

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + agent);
    command.add("-Dotel.javaagent.extensions=" + extension);
    if (Boolean.getBoolean("quarkus.smoke.agent.debug")) {
      command.add("-Dotel.javaagent.debug=true");
    }
    command.add("-Dotel.service.name=o11y-quarkus-smoke");
    command.add("-Dotel.traces.exporter=logging");
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.logs.exporter=logging");
    command.add("-Dotel.bsp.schedule.delay=100");
    command.add("-Dotel.blrp.schedule.delay=100");
    command.add("-Dotel.metric.export.interval=60000");
    command.add("-Dquarkus.http.port=" + port);
    command.add("-Dquarkus.http.host=127.0.0.1");
    command.add("-Do11y.quarkus.rest.smoke.diagnostics=true");
    command.add("-Do11y.dynamic.policy.active-generation=" + GENERATION);
    command.add(
        "-Do11y.dynamic.request.headers.generation."
            + GENERATION
            + "=x-client-segment");
    command.add(
        "-Do11y.dynamic.response.headers.generation."
            + GENERATION
            + "=x-rate-type");
    command.add("-Do11y.dynamic.body.compiled=" + policy.http());
    command.add("-Do11y.dynamic.body.compiled.generation." + GENERATION + '=' + policy.http());
    command.add("-Do11y.dynamic.method.compiled=" + policy.method());
    command.add("-Do11y.dynamic.method.compiled.generation." + GENERATION + '=' + policy.method());
    command.add("-jar");
    command.add(runner.toString());

    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put("OPAMP_ENDPOINT", "http://127.0.0.1:1/v1/opamp");
    Process process = processBuilder.start();
    StringBuilder output = new StringBuilder();
    Thread reader = outputReader(process, output);
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(1))
              .build();
      waitUntilReady(client, port, process, output);

      String requestBody =
          "{\"channel\":\"WEB\",\"amount\":2500.0,\"marker\":\"request-intact\"}";
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + port
                              + "/api/exchanges/acct-42?campaign=JULY"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/json")
                  .header("X-Client-Segment", "SALARY_ACCOUNT")
                  .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(201, response.statusCode());
      assertEquals("QUARKUS_LTS", response.headers().firstValue("X-Rate-Type").orElseThrow());
      assertTrue(response.body().contains("\"marker\":\"request-intact\""));
      assertTrue(response.body().contains("\"sourceAmount\":2500.0"));
      assertTrue(response.body().contains("\"targetAmount\":718.76"));

      Thread.sleep(1200);
      HttpResponse<String> state =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/__o11y-state"))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if ("quarkus-rest".equals(System.getProperty("quarkus.smoke.stack", ""))) {
        assertEquals(
            "1",
            state.body(),
            "only the diagnostics request itself may remain active; the exchange must be released");
      } else {
        assertEquals(
            "unavailable",
            state.body(),
            "RESTEasy Classic must use the Servlet module, not the Quarkus REST helper");
      }

      try {
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__stop"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding());
      } catch (IOException ignored) {
        // Quarkus may close the loopback socket as soon as asyncExit is scheduled.
      }
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Quarkus fixture did not stop");
      reader.join(Duration.ofSeconds(3));

      String telemetry = synchronizedOutput(output);
      assertEquals(0, process.exitValue(), () -> "Quarkus fixture failed:\n" + telemetry);
      assertEquals(
          1,
          occurrences(telemetry, "INFO 'Quarkus REST exchange matched'"),
          () -> "HTTP rule must emit one correlated log:\n" + telemetry);
      assertEquals(
          1,
          occurrences(telemetry, "INFO 'Quarkus exchange method matched'"),
          () -> "method rule must emit one correlated log:\n" + telemetry);
      assertTrue(
          telemetry.contains("quarkus.client.segment=\"SALARY_ACCOUNT\"")
              && telemetry.contains("quarkus.request.campaign=\"JULY\"")
              && telemetry.contains("quarkus.account.id=\"acct-42\"")
              && telemetry.contains("quarkus.client.channel=\"WEB\"")
              && telemetry.contains("quarkus.rate.type=\"QUARKUS_LTS\""),
          () -> "HTTP header/query/path/body attributes are missing:\n" + telemetry);
      assertTrue(
          telemetry.contains("quarkus.exchange.source.amount=2500.0")
              && telemetry.contains("quarkus.exchange.target.amount=718.76"),
          () -> "HTTP request/response body attributes are missing:\n" + telemetry);
      assertTrue(
          telemetry.lines()
              .anyMatch(
                  line ->
                      line.contains(" SERVER [tracer:")
                          && line.contains("http.request.header.x_client_segment")
                          && line.contains("http.response.header.x_rate_type")
                          && line.contains("quarkus.client.segment")
                          && line.contains("quarkus.request.campaign")
                          && line.contains("quarkus.account.id")
                          && line.contains("quarkus.exchange.source.amount")
                          && line.contains("quarkus.exchange.target.amount")),
          () -> "HTTP policy attributes are not attached to the Quarkus server span:\n" + telemetry);
      assertTrue(
          telemetry.lines()
              .anyMatch(
                  line ->
                      line.contains(" SERVER [tracer:")
                          && line.contains("quarkus.exchange.method.source.amount=2500.0")
                          && line.contains("quarkus.exchange.method.target.amount=718.76")),
          () -> "Quarkus method attributes are not attached to the server span:\n" + telemetry);
      assertTrue(
          Pattern.compile(
                  "name=quarkus\\.rest\\.exchange\\.operations[^\\n]*"
                      + "unit=\\{operation}, type=DOUBLE_SUM[^\\n]*value=1\\.0")
              .matcher(telemetry)
              .find(),
          () -> "HTTP event counter missing:\n" + telemetry);
      assertFalse(
          telemetry.contains("name=quarkus.rest.exchange.operations")
              && telemetry.contains("value=2.0"),
          () -> "HTTP event was emitted more than once:\n" + telemetry);
      assertTrue(
          Pattern.compile(
                  "name=quarkus\\.exchange\\.method\\.target\\.amount[^\\n]*"
                      + "unit=\\{USD}, type=HISTOGRAM[^\\n]*getSum=718\\.76, getCount=1")
              .matcher(telemetry)
              .find(),
          () -> "Quarkus method histogram missing:\n" + telemetry);
      Matcher correlated =
          Pattern.compile(
                  "(?m)INFO 'Quarkus REST exchange matched' : ([0-9a-f]{32}) ([0-9a-f]{16}) ")
              .matcher(telemetry);
      assertTrue(correlated.find(), () -> "correlated Quarkus REST log missing:\n" + telemetry);
      assertNotEquals("00000000000000000000000000000000", correlated.group(1));
      assertNotEquals("0000000000000000", correlated.group(2));
    } finally {
      process.destroy();
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
  }

  private static Path optionalFixture() {
    String configured = System.getProperty("quarkus.fixture.http.runner", "");
    assumeTrue(!configured.isBlank(), "Quarkus smoke profile is not active");
    Path runner = Path.of(configured).toAbsolutePath().normalize();
    assumeTrue(Files.isRegularFile(runner), "Quarkus fast-jar fixture is not built");
    return runner;
  }

  private static Path requiredFile(String property) {
    Path path = Path.of(System.getProperty(property, "")).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(path), () -> "missing test artifact: " + path);
    return path;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Thread outputReader(Process process, StringBuilder output) {
    return Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              try (BufferedReader lines = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                  synchronized (output) {
                    output.append(line).append('\n');
                  }
                }
              } catch (IOException ignored) {
                // The process teardown closes the stream.
              }
            });
  }

  private static void waitUntilReady(
      HttpClient client, int port, Process process, StringBuilder output) throws Exception {
    URI health = URI.create("http://127.0.0.1:" + port + "/healthz");
    for (int attempt = 0; attempt < 120; attempt++) {
      if (!process.isAlive()) {
        throw new AssertionError("Quarkus stopped before readiness:\n" + synchronizedOutput(output));
      }
      try {
        HttpResponse<Void> response =
            client.send(
                HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(1)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 204) {
          return;
        }
      } catch (IOException ignored) {
        // Startup has not bound the loopback socket yet.
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Quarkus did not become ready:\n" + synchronizedOutput(output));
  }

  private static String synchronizedOutput(StringBuilder output) {
    synchronized (output) {
      return output.toString();
    }
  }

  private static int occurrences(String value, String token) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(token, offset)) >= 0) {
      count++;
      offset += token.length();
    }
    return count;
  }

  private String resource(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      if (input == null) {
        throw new IOException("missing policy resource " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
