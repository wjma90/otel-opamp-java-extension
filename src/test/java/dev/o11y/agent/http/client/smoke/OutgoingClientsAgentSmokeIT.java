package dev.o11y.agent.http.client.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import dev.o11y.agent.policy.PolicyState;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
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

class OutgoingClientsAgentSmokeIT {
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);

  @Test
  void javaAgentCapturesBodiesForAllSupportedOutgoingClientsWithoutChangingThem() throws Exception {
    Path agent = requiredFile("opentelemetry.javaagent.jar");
    Path extension = requiredFile("packaged.extension.jar");
    Path fixture = createFixtureJar();
    String policy = resource("outgoing-http-clients-business-event.json");
    String compiled = PolicyState.applyJson(policy).compiledBodyPolicy();

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("--add-modules");
    command.add("jdk.httpserver");
    command.add("-javaagent:" + agent);
    command.add("-Dotel.javaagent.extensions=" + extension);
    command.add("-Dotel.service.name=o11y-outgoing-clients-smoke");
    command.add("-Dotel.traces.exporter=logging");
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.logs.exporter=logging");
    command.add("-Dotel.bsp.schedule.delay=100");
    command.add("-Dotel.blrp.schedule.delay=100");
    command.add("-Dotel.metric.export.interval=800");
    command.add("-Dotel.instrumentation.runtime-telemetry.enabled=false");
    command.add("-Dotel.instrumentation.runtime-telemetry-java8.enabled=false");
    command.add("-D" + HttpBodyPolicyEngine.POLICY_PROPERTY + '=' + compiled);
    command.add("-cp");
    command.add(isolatedFixtureClasspath(fixture));
    command.add(OutgoingClientsSmokeApplication.class.getName());

    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put("OPAMP_ENDPOINT", "http://127.0.0.1:1/v1/opamp");
    Process process = processBuilder.start();
    StringBuilder output = new StringBuilder();
    Thread reader = outputReader(process, output);
    try {
      assertTrue(
          process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
          () -> "outgoing smoke application did not stop:\n" + snapshot(output));
      reader.join(Duration.ofSeconds(3));
      String telemetry = snapshot(output);

      assertEquals(0, process.exitValue(), () -> "smoke process failed:\n" + telemetry);
      assertTrue(
          telemetry.contains("O11Y_OUTGOING_BODIES_PRESERVED=9"),
          () -> "a client request or response body changed:\n" + telemetry);
      assertTrue(
          telemetry.contains("O11Y_OUTGOING_SMOKE_OK"),
          () -> "the child process did not complete all clients:\n" + telemetry);

      assertClientSpan(telemetry, "spring", 100, 50);
      assertClientSpan(telemetry, "apache", 200, 100);
      assertClientSpan(telemetry, "okhttp-sync", 300, 150);
      assertClientSpan(telemetry, "okhttp-async", 400, 200);
      assertClientSpan(telemetry, "webclient", 500, 250);
      assertClientSpan(telemetry, "jdk-sync", 600, 300);
      assertClientSpan(telemetry, "jdk-async", 700, 350);
      assertClientSpan(telemetry, "apache5", 800, 400);
      assertClientSpan(telemetry, "apache5-async", 900, 450);

      assertTrue(
          Pattern.compile(
                  "name=o11y\\.smoke\\.outgoing\\.operations[^\\n]*"
                      + "unit=\\{operation}, type=DOUBLE_SUM")
              .matcher(telemetry)
              .find(),
          () -> "outgoing counter is missing:\n" + telemetry);
      for (String library :
          List.of(
              "spring",
              "apache",
              "okhttp-sync",
              "okhttp-async",
              "webclient",
              "jdk-sync",
              "jdk-async",
              "apache5",
              "apache5-async")) {
        assertTrue(
            telemetry.contains(
                "attributes={test.outgoing.request.campaign=\"JULY\", "
                    + "test.outgoing.request.client=\""
                    + library
                    + "\"}, value=1.0"),
            () -> "bounded header/query dimensions are missing for " + library + ":\n" + telemetry);
      }
      assertTrue(
          Pattern.compile(
                  "name=o11y\\.smoke\\.outgoing\\.amount[^\\n]*"
                      + "unit=\\{currency}, type=HISTOGRAM[^\\n]*"
                      + "getSum=4500\\.0, getCount=9")
              .matcher(telemetry)
              .find(),
          () -> "aggregated outgoing amount histogram is missing:\n" + telemetry);

      Matcher correlatedLogs =
          Pattern.compile(
                  "(?m)INFO 'Outgoing exchange approved' : "
                      + "([0-9a-f]{32}) ([0-9a-f]{16}) ")
              .matcher(telemetry);
      int logCount = 0;
      while (correlatedLogs.find()) {
        assertNotEquals("00000000000000000000000000000000", correlatedLogs.group(1));
        assertNotEquals("0000000000000000", correlatedLogs.group(2));
        logCount++;
      }
      assertEquals(9, logCount, () -> "expected nine correlated business logs:\n" + telemetry);
    } finally {
      process.destroy();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      Files.deleteIfExists(fixture);
    }
  }

  private static void assertClientSpan(
      String telemetry, String library, double requestAmount, double responseAmount) {
    String expectedRequest = "test.outgoing.request.amount=" + requestAmount;
    String expectedResponse = "test.outgoing.response.amount=" + responseAmount;
    String expectedRequestHeader = "test.outgoing.request.client=" + library;
    String expectedResponseHeader = "test.outgoing.response.result=approved-" + library;
    String expectedQuery = "test.outgoing.request.campaign=JULY";
    boolean found =
        telemetry.lines()
            .anyMatch(
                line ->
                    line.contains(" CLIENT [")
                        && line.contains("test.outgoing.library=" + library)
                        && line.contains(expectedRequest)
                        && line.contains(expectedResponse)
                        && line.contains(expectedRequestHeader)
                        && line.contains(expectedResponseHeader)
                        && line.contains(expectedQuery));
    assertTrue(
        found,
        () ->
            "OUTGOING attributes are missing from the "
                + library
                + " CLIENT span:\n"
                + telemetry);
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
              } catch (Exception ignored) {
                // Process exit closes the stream; assertions report captured output.
              }
            });
  }

  private static String snapshot(StringBuilder output) {
    synchronized (output) {
      return output.toString();
    }
  }

  private static Path createFixtureJar() throws Exception {
    Path jar = Files.createTempFile("o11y-outgoing-clients-smoke-fixture-", ".jar");
    List<Class<?>> classes = new ArrayList<>();
    classes.add(OutgoingClientsSmokeApplication.class);
    classes.add(SpringRestClientSmokeCall.class);
    classes.add(SpringWebClientSmokeCall.class);
    classes.add(JdkHttpClientSmokeCall.class);
    classes.add(Apache5ClientSmokeCall.class);
    classes.addAll(Arrays.asList(OutgoingClientsSmokeApplication.class.getDeclaredClasses()));
    classes.addAll(Arrays.asList(SpringWebClientSmokeCall.class.getDeclaredClasses()));
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
            .filter(OutgoingClientsAgentSmokeIT::isFixtureDependency)
            .map(path -> path.toAbsolutePath().normalize().toString())
            .toList();
    assertTrue(
        dependencies.stream().anyMatch(path -> path.contains("spring-web-")),
        "isolated smoke classpath has no Spring RestClient");
    assertTrue(
        dependencies.stream().anyMatch(path -> path.contains("httpclient-4.5.14")),
        "isolated smoke classpath has no Apache HttpClient 4.5.14");
    assertTrue(
        dependencies.stream().anyMatch(path -> path.contains("okhttp-jvm-5.3.2")),
        "isolated smoke classpath has no OkHttp 5.3.2");
    ArrayList<String> entries = new ArrayList<>();
    entries.add(fixture.toAbsolutePath().normalize().toString());
    entries.addAll(dependencies);
    return String.join(separator, entries);
  }

  private static boolean isFixtureDependency(Path path) {
    String name = path.getFileName().toString();
    return name.startsWith("spring-web-")
        || name.startsWith("spring-core-")
        || name.startsWith("spring-beans-")
        || name.startsWith("spring-context-")
        || name.startsWith("spring-expression-")
        || name.startsWith("spring-webflux-")
        || name.startsWith("spring-jcl-")
        || name.startsWith("micrometer-observation-")
        || name.startsWith("micrometer-commons-")
        || name.startsWith("jspecify-")
        || name.startsWith("httpclient-")
        || name.startsWith("httpcore-")
        || name.startsWith("httpclient5-")
        || name.startsWith("httpcore5-")
        || name.startsWith("httpcore5-h2-")
        || name.startsWith("commons-logging-")
        || name.startsWith("commons-codec-")
        || name.startsWith("okhttp-jvm-")
        || name.startsWith("okio-jvm-")
        || name.startsWith("reactor-core-")
        || name.startsWith("reactor-netty-")
        || name.startsWith("reactive-streams-")
        || name.startsWith("netty-")
        || name.startsWith("slf4j-api-")
        || name.startsWith("kotlin-stdlib-")
        || name.startsWith("annotations-");
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
}
