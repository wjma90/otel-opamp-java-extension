package dev.o11y.agent.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import dev.o11y.agent.policy.PolicyState;
import java.io.BufferedReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** Real Java Agent smoke over Quarkus SmallRye Kafka and Quarkus Artemis JMS in JVM mode. */
class QuarkusMessagingAgentSmokeIT {
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(25);
  private static final String TOPIC = "o11y.quarkus.exchange";

  @Test
  void appliesProducerAndConsumerPoliciesThroughTheQuarkusMessagingIntegrations()
      throws Exception {
    String configuredRunner =
        System.getProperty("quarkus.fixture.messaging.runner", "").trim();
    assumeFalse(configuredRunner.isEmpty(), "activate -Pquarkus-smoke to build the fixture");

    Path runner = Path.of(configuredRunner).toAbsolutePath().normalize();
    Path agent = requiredFile("opentelemetry.javaagent.jar");
    Path extension = requiredFile("packaged.extension.jar");
    assertTrue(Files.isRegularFile(runner), "the Quarkus fixture fast-jar is missing");

    int artemisPort = availablePort();
    int applicationPort = availablePort();
    Path artemisData = Files.createTempDirectory("o11y-quarkus-artemis-");
    EmbeddedKafkaKraftBroker kafka = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
    EmbeddedActiveMQ artemis = null;
    RunningApplication application = null;
    StringBuilder output = new StringBuilder();
    try {
      kafka.afterPropertiesSet();
      artemis = startArtemis(artemisPort, artemisData);
      application =
          startApplication(
              runner,
              agent,
              extension,
              applicationPort,
              kafka.getBrokersAsString(),
              artemisPort,
              output);
      Process runningApplication = application.process();

      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(2))
              .build();
      URI base = URI.create("http://127.0.0.1:" + applicationPort);
      awaitHealthy(client, base, runningApplication, output);

      HttpResponse<String> kafkaResponse =
          post(client, base.resolve("/smoke/messaging/kafka"), request(150));
      assertEquals(202, kafkaResponse.statusCode(), kafkaResponse.body());
      awaitKafkaConsumption(client, base, runningApplication, output);

      HttpResponse<String> jmsResponse =
          post(client, base.resolve("/smoke/messaging/jms"), request(250));
      assertEquals(200, jmsResponse.statusCode(), jmsResponse.body());
      assertTrue(jmsResponse.body().contains("\"amount\":250.00"), jmsResponse.body());

      Thread.sleep(2200);
      post(client, base.resolve("/__stop"), "{}");
      assertTrue(
          runningApplication.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
          () -> "Quarkus messaging fixture did not stop:\n" + snapshot(output));
      application.outputReader().join(2000);
      String telemetry = snapshot(output);
      assertEquals(
          0,
          runningApplication.exitValue(),
          () -> "Quarkus fixture failed:\n" + telemetry);

      assertOccurrences(telemetry, "Quarkus Kafka producer matched", 1);
      assertOccurrences(telemetry, "Quarkus Kafka consumer matched", 1);
      assertOccurrences(telemetry, "Quarkus JMS producer matched", 1);
      assertOccurrences(telemetry, "Quarkus JMS consumer matched", 1);
      assertHistogram(telemetry, "o11y.quarkus.kafka.producer.amount", 150);
      assertHistogram(telemetry, "o11y.quarkus.kafka.consumer.amount", 150);
      assertHistogram(telemetry, "o11y.quarkus.jms.producer.amount", 250);
      assertHistogram(telemetry, "o11y.quarkus.jms.consumer.amount", 250);
      assertSpanAttribute(
          telemetry, "PRODUCER", "test.quarkus.kafka.producer.amount", 150);
      assertSpanAttribute(
          telemetry, "CONSUMER", "test.quarkus.kafka.consumer.amount", 150);
      assertSpanAttribute(
          telemetry, "SERVER", "test.quarkus.jms.producer.amount", 250);
      assertSpanAttribute(
          telemetry, "SERVER", "test.quarkus.jms.consumer.amount", 250);
    } finally {
      if (application != null) {
        Process process = application.process();
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
        application.outputReader().join(2000);
      }
      if (artemis != null) {
        artemis.stop();
      }
      kafka.destroy();
      deleteTree(artemisData);
    }
  }

  private RunningApplication startApplication(
      Path runner,
      Path agent,
      Path extension,
      int applicationPort,
      String kafkaBrokers,
      int artemisPort,
      StringBuilder output)
      throws Exception {
    String policy = resource("quarkus-messaging-event.json");
    String compiled = PolicyState.applyJson(policy).compiledBodyPolicy();
    ProcessBuilder builder = new ProcessBuilder("java");
    List<String> command = builder.command();
    command.add("-javaagent:" + agent);
    command.add("-Dotel.javaagent.extensions=" + extension);
    command.add("-Dotel.service.name=o11y-quarkus-messaging-smoke");
    command.add("-Dotel.traces.exporter=logging");
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.logs.exporter=logging");
    command.add("-Dotel.bsp.schedule.delay=100");
    command.add("-Dotel.blrp.schedule.delay=100");
    command.add("-Dotel.metric.export.interval=600");
    command.add("-Dotel.instrumentation.runtime-telemetry.enabled=false");
    command.add("-Dotel.instrumentation.runtime-telemetry-java8.enabled=false");
    command.add("-D" + HttpBodyPolicyEngine.POLICY_PROPERTY + '=' + compiled);
    command.add("-Dquarkus.http.port=" + applicationPort);
    command.add("-Do11y.messaging.enabled=true");
    command.add("-Dkafka.bootstrap.servers=" + kafkaBrokers);
    command.add("-Dquarkus.artemis.url=tcp://127.0.0.1:" + artemisPort);
    command.add("-Dquarkus.artemis.username=");
    command.add("-Dquarkus.artemis.password=");
    command.add("-jar");
    command.add(runner.toString());

    builder.redirectErrorStream(true);
    builder.environment().put("OPAMP_ENDPOINT", "http://127.0.0.1:1/v1/opamp");
    Process process = builder.start();
    Thread outputReader =
        Thread.ofPlatform()
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
                  } catch (Exception ignored) {
                    // Process exit closes the stream; assertions include all output captured so far.
                  }
                });
    return new RunningApplication(process, outputReader);
  }

  private static EmbeddedActiveMQ startArtemis(int port, Path data) throws Exception {
    ConfigurationImpl configuration =
        new ConfigurationImpl()
            .setName("o11y-quarkus-smoke")
            .setPersistenceEnabled(false)
            .setSecurityEnabled(false)
            .setJMXManagementEnabled(false)
            .setJournalDirectory(data.resolve("journal").toString())
            .setBindingsDirectory(data.resolve("bindings").toString())
            .setPagingDirectory(data.resolve("paging").toString())
            .setLargeMessagesDirectory(data.resolve("large-messages").toString())
            .addAcceptorConfiguration("netty", "tcp://127.0.0.1:" + port);
    return new EmbeddedActiveMQ().setConfiguration(configuration).start();
  }

  private static void awaitHealthy(
      HttpClient client, URI base, Process process, StringBuilder output) throws Exception {
    long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline && process.isAlive()) {
      try {
        HttpResponse<Void> response =
            client.send(
                HttpRequest.newBuilder(base.resolve("/healthz"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 204) {
          return;
        }
      } catch (Exception ignored) {
        // Quarkus and both connectors are still starting.
      }
      Thread.sleep(200);
    }
    throw new AssertionError("Quarkus messaging fixture did not start:\n" + snapshot(output));
  }

  private static void awaitKafkaConsumption(
      HttpClient client, URI base, Process process, StringBuilder output) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline && process.isAlive()) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(base.resolve("/smoke/messaging/kafka/last"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200 && response.body().contains("\"amount\":150.00")) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("SmallRye Kafka did not consume the record:\n" + snapshot(output));
  }

  private static HttpResponse<String> post(HttpClient client, URI uri, String json)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void assertHistogram(String telemetry, String name, double sum) {
    Pattern expected =
        Pattern.compile(
            "name="
                + Pattern.quote(name)
                + "[^\\n]*type=HISTOGRAM[^\\n]*getSum="
                + sum
                + ", getCount=(\\d+)");
    var matches = expected.matcher(telemetry);
    boolean found = false;
    while (matches.find()) {
      found = true;
      assertEquals(
          1,
          Integer.parseInt(matches.group(1)),
          () -> "duplicate logical measurements for " + name + ":\n" + telemetry);
    }
    assertTrue(found, () -> "missing " + name + ":\n" + telemetry);
  }

  private static void assertOccurrences(String telemetry, String value, int expected) {
    int count = 0;
    int index = 0;
    while ((index = telemetry.indexOf(value, index)) >= 0) {
      count++;
      index += value.length();
    }
    assertEquals(
        expected,
        count,
        () -> "unexpected occurrence count for " + value + ":\n" + telemetry);
  }

  private static void assertSpanAttribute(
      String telemetry, String spanKind, String attribute, double value) {
    assertTrue(
        telemetry.lines()
            .anyMatch(
                line ->
                    line.contains(" " + spanKind + " [tracer:")
                        && line.contains(attribute + '=' + value)),
        () ->
            "missing "
                + attribute
                + " on a "
                + spanKind
                + " span:\n"
                + telemetry);
  }

  private static String request(double amount) {
    return "{\"channel\":\"WEB\",\"amount\":" + amount + '}';
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      if (input == null) {
        throw new IllegalStateException("Missing policy resource " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Path requiredFile(String property) {
    Path path = Path.of(System.getProperty(property, "")).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(path), () -> "missing test artifact: " + path);
    return path;
  }

  private static int availablePort() throws Exception {
    try (ServerSocketChannel channel = ServerSocketChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }
  }

  private static String snapshot(StringBuilder output) {
    synchronized (output) {
      return output.toString();
    }
  }

  private static void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private record RunningApplication(Process process, Thread outputReader) {}
}
