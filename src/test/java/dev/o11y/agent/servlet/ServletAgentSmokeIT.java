package dev.o11y.agent.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.policy.PolicyState;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServletAgentSmokeIT {
  @Test
  void javaAgentCapturesRequestAndResponseWithoutAnApplicationRuntimeJar() throws Exception {
    Path agent = requiredFile("opentelemetry.javaagent.jar");
    Path extension = requiredFile("packaged.extension.jar");
    Path fixture = createFixtureJar();
    String policy = resource("http-body-business-event.json");
    String compiled = PolicyState.applyJson(policy).compiledBodyPolicy();

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + agent);
    command.add("-Dotel.javaagent.extensions=" + extension);
    command.add("-Dotel.service.name=o11y-servlet-smoke");
    command.add("-Dotel.traces.exporter=logging");
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.logs.exporter=logging");
    command.add("-Dotel.bsp.schedule.delay=100");
    command.add("-Dotel.blrp.schedule.delay=100");
    command.add("-Dotel.metric.export.interval=500");
    command.add("-D" + HttpBodyPolicyEngine.POLICY_PROPERTY + '=' + compiled);
    command.add("-cp");
    command.add(isolatedFixtureClasspath(fixture));
    command.add(ServletSmokeApplication.class.getName());

    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put("OPAMP_ENDPOINT", "http://127.0.0.1:1/v1/opamp");
    Process process = processBuilder.start();
    StringBuilder output = new StringBuilder();
    java.util.concurrent.CompletableFuture<Integer> port = new java.util.concurrent.CompletableFuture<>();
    Thread reader =
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
                      if (line.startsWith("O11Y_SMOKE_READY=")) {
                        port.complete(Integer.parseInt(line.substring(line.indexOf('=') + 1)));
                      }
                    }
                  } catch (Exception error) {
                    port.completeExceptionally(error);
                  }
                });
    try {
      int serverPort;
      try {
        serverPort = port.get(20, TimeUnit.SECONDS);
      } catch (Exception error) {
        process.destroyForcibly();
        reader.join(Duration.ofSeconds(2));
        String startup;
        synchronized (output) {
          startup = output.toString();
        }
        throw new AssertionError("smoke application did not start:\n" + startup, error);
      }
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + serverPort
                              + "/api/exchanges?campaign=JULY"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/json")
                  .header("X-Client-Segment", "SALARY_ACCOUNT")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          """
                          {"operation":"EXCHANGE","sourceCurrency":"PEN",
                           "targetCurrency":"USD","channel":"WEB","amount":2500}
                          """))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode());

      Thread.sleep(1600);
      client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/__stop"))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.discarding());
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "smoke application did not stop");
      reader.join(Duration.ofSeconds(2));

      String telemetry;
      synchronized (output) {
        telemetry = output.toString();
      }
      assertTrue(
          telemetry.contains("cambistapp.currency_exchange.source.amount=2500.0"),
          () -> "request body span attribute missing:\n" + telemetry);
      assertTrue(
          telemetry.contains("[scopeInfo: dev.o11y.http-telemetry-events:]"),
          () -> "HTTP telemetry event scope is missing:\n" + telemetry);
      assertTrue(
          telemetry.contains("cambistapp.currency_exchange.target.amount=718.76"),
          () -> "response body span attribute missing:\n" + telemetry);
      assertTrue(
          telemetry.contains("cambistapp.client.segment=\"SALARY_ACCOUNT\"")
              && telemetry.contains("cambistapp.request.campaign=\"JULY\"")
              && telemetry.contains("cambistapp.currency_exchange.rate.source=\"INTERNAL\""),
          () -> "header/query event attributes are missing:\n" + telemetry);
      assertTrue(
          Pattern.compile(
                  "name=cambistapp\\.currency_exchange\\.operations[^\\n]*"
                      + "unit=\\{operation}, type=DOUBLE_SUM[^\\n]*value=1\\.0")
              .matcher(telemetry)
              .find(),
          () -> "business counter missing:\n" + telemetry);
      assertTrue(
          Pattern.compile(
                  "name=cambistapp\\.currency_exchange\\.operations[^\\n]*"
                      + "attributes=\\{"
                      + "(?=[^\\n}]*http\\.request\\.method=\"POST\")"
                      + "(?=[^\\n}]*http\\.route=\"/api/exchanges\")"
                      + "(?=[^\\n}]*http\\.response\\.status_code=201)"
                      + "[^\\n}]*}, value=1\\.0")
              .matcher(telemetry)
              .find(),
          () -> "HTTP contextual metric attributes are missing:\n" + telemetry);
      assertTrue(
          Pattern.compile(
                  "name=cambistapp\\.currency_exchange\\.source\\.amount[^\\n]*"
                      + "unit=\\{PEN}, type=HISTOGRAM[^\\n]*getSum=2500\\.0, getCount=1")
              .matcher(telemetry)
              .find(),
          () -> "business amount histogram missing:\n" + telemetry);
      Matcher correlatedLog =
          Pattern.compile(
                  "(?m)INFO 'Currency exchange approved' : ([0-9a-f]{32}) ([0-9a-f]{16}) ")
              .matcher(telemetry);
      assertTrue(correlatedLog.find(), () -> "correlated business log missing:\n" + telemetry);
      assertNotEquals("00000000000000000000000000000000", correlatedLog.group(1));
      assertNotEquals("0000000000000000", correlatedLog.group(2));
      assertEquals(0, process.exitValue(), () -> "smoke process failed:\n" + telemetry);
    } finally {
      process.destroy();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      Files.deleteIfExists(fixture);
    }
  }

  private static Path createFixtureJar() throws Exception {
    Path jar = Files.createTempFile("o11y-servlet-smoke-fixture-", ".jar");
    List<Class<?>> classes = new ArrayList<>();
    classes.add(ServletSmokeApplication.class);
    classes.addAll(Arrays.asList(ServletSmokeApplication.class.getDeclaredClasses()));
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      for (Class<?> type : classes) {
        String entryName = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(entryName));
        try (InputStream input = type.getClassLoader().getResourceAsStream(entryName)) {
          if (input == null) {
            throw new IllegalStateException("Missing compiled smoke fixture " + entryName);
          }
          input.transferTo(output);
        }
        output.closeEntry();
      }
    }
    return jar;
  }

  private static String isolatedFixtureClasspath(Path fixture) {
    String separator = File.pathSeparator;
    List<String> dependencies =
        Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(separator)))
            .map(Path::of)
            .filter(
                path -> {
                  String name = path.getFileName().toString();
                  return name.startsWith("tomcat-embed-core-")
                      || name.startsWith("tomcat-annotations-api-")
                      || name.startsWith("jakarta.servlet-api-");
                })
            .map(path -> path.toAbsolutePath().normalize().toString())
            .toList();
    assertTrue(!dependencies.isEmpty(), "isolated smoke classpath has no Servlet container");
    ArrayList<String> entries = new ArrayList<>();
    entries.add(fixture.toAbsolutePath().normalize().toString());
    entries.addAll(dependencies);
    return String.join(separator, entries);
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Path requiredFile(String property) {
    Path path = Path.of(System.getProperty(property, "")).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(path), () -> "missing test artifact: " + path);
    return path;
  }
}
