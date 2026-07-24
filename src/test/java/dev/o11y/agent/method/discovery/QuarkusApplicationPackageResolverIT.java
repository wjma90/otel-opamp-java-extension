package dev.o11y.agent.method.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusApplicationPackageResolverIT {
  @Test
  void resolvesTheRealQuarkusFastJarApplicationBoundary() {
    String configuredRunner = System.getProperty("quarkus.fixture.runner", "").trim();
    assumeFalse(configuredRunner.isEmpty(), "activate -Pquarkus-smoke to build the fixture");
    Path runner = Path.of(configuredRunner).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(runner), "the Quarkus profile must build its fast-jar first");

    var resolution = ApplicationPackageResolver.resolve(null, runner.toString(), "");

    assertEquals(List.of("dev.o11y.quarkus"), resolution.packagePrefixes());
    assertEquals("quarkus-fast-jar", resolution.source());
  }
}
