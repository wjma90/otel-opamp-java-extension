package dev.o11y.agent.method.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationPackageResolverTest {
  @TempDir Path temporaryDirectory;

  @Test
  void explicitOverrideWinsAndKeepsOnlySafePackageBoundaries() throws Exception {
    Path application = springBootJar("dev.other.Application");

    var resolution =
        ApplicationPackageResolver.resolve(
            " com.acme.exchange.service,java.lang,com,dev.o11y,org.springframework.context,"
                + "io.opentelemetry.api,net.bytebuddy.matcher,com.acme.exchange.service ",
            application.toString(),
            "");

    assertEquals(List.of("com.acme.exchange.service"), resolution.packagePrefixes());
    assertEquals("override", resolution.source());
  }

  @Test
  void invalidExplicitOverrideFailsClosedInsteadOfFallingBackToDiscovery()
      throws Exception {
    Path application = springBootJar("com.acme.ExchangeApplication");

    var resolution =
        ApplicationPackageResolver.resolve(
            "java.lang,com,dev.o11y,org.apache.http,io.netty.channel,reactor.core,"
                + "com.fasterxml.jackson,"
                + "io.quarkus.runner,kotlin.collections,scala.collection",
            application.toString(),
            "");

    assertTrue(resolution.packagePrefixes().isEmpty());
    assertEquals("override", resolution.source());
  }

  @Test
  void springBootJarUsesStartClassBeforeItsLauncherMainClass() throws Exception {
    Path application = springBootJar("com.acme.exchange.ExchangeApplication");

    var resolution =
        ApplicationPackageResolver.resolve(
            null, '"' + application.toString() + "\" --server.port=8080", "");

    assertEquals(List.of("com.acme.exchange"), resolution.packagePrefixes());
    assertEquals("start-class", resolution.source());
  }

  @Test
  void executableJarFallsBackToItsApplicationMainClass() throws Exception {
    Path application =
        jar(
            "plain-application.jar",
            null,
            "org.acme.rates.RatesApplication");

    var resolution =
        ApplicationPackageResolver.resolve(null, application + " --spring.profiles.active=local", "");

    assertEquals(List.of("org.acme.rates"), resolution.packagePrefixes());
    assertEquals("main-class", resolution.source());
  }

  @Test
  void directAndModularMainClassesResolveTheirContainingPackage() {
    var direct =
        ApplicationPackageResolver.resolve(
            null, "dev.example.billing.BillingApplication one two", "");
    var modular =
        ApplicationPackageResolver.resolve(
            null, "billing.module/dev.example.billing.BillingApplication", "");

    assertEquals(List.of("dev.example.billing"), direct.packagePrefixes());
    assertEquals("main-class", direct.source());
    assertEquals(List.of("dev.example.billing"), modular.packagePrefixes());
    assertEquals("main-class", modular.source());
  }

  @Test
  void knownFrameworkLauncherCanUseOneUnambiguousClasspathStartClass()
      throws Exception {
    Path application = springBootJar("com.acme.orders.OrdersApplication");

    var resolution =
        ApplicationPackageResolver.resolve(
            null,
            "org.springframework.boot.loader.launch.JarLauncher",
            application.toString());

    assertEquals(List.of("com.acme.orders"), resolution.packagePrefixes());
    assertEquals("start-class", resolution.source());
  }

  @Test
  void ambiguousClasspathAndFrameworkPackagesFailClosed() throws Exception {
    Path first = springBootJar("com.acme.orders.OrdersApplication");
    Path second =
        jar(
            "other-application.jar",
            "org.example.shipping.ShippingApplication",
            "org.springframework.boot.loader.launch.JarLauncher");

    var ambiguous =
        ApplicationPackageResolver.resolve(
            null,
            "org.springframework.boot.loader.launch.JarLauncher",
            first + File.pathSeparator + second);
    var platform =
        ApplicationPackageResolver.resolve(null, "java.lang.String", "");

    assertTrue(ambiguous.packagePrefixes().isEmpty());
    assertEquals("none", ambiguous.source());
    assertTrue(platform.packagePrefixes().isEmpty());
    assertEquals("none", platform.source());
  }

  @Test
  void quarkusFastJarUsesOnlyTheApplicationArtifact() throws Exception {
    Path distribution = temporaryDirectory.resolve("quarkus app");
    Path launcher =
        jarAt(
            distribution.resolve("quarkus-run.jar"),
            null,
            "io.quarkus.bootstrap.runner.QuarkusEntryPoint",
            null,
            List.of());
    Path metadata = distribution.resolve("quarkus/quarkus-application.dat");
    Files.createDirectories(metadata.getParent());
    Files.createFile(metadata);
    jarAt(
        distribution.resolve("app/cambistapp.jar"),
        null,
        null,
        null,
        List.of(
            "com/acme/exchange/ExchangeApplication.class",
            "com/acme/exchange/service/ExchangeService.class",
            "com/acme/exchange/controller/ExchangeResource.class"));
    jarAt(
        distribution.resolve("lib/main/vendor-client.jar"),
        null,
        null,
        null,
        List.of("com/vendor/client/HttpClient.class"));

    var resolution =
        ApplicationPackageResolver.resolve(
            null, '"' + launcher.toString() + "\" --profile=prod", "");

    assertEquals(List.of("com.acme.exchange"), resolution.packagePrefixes());
    assertEquals("quarkus-fast-jar", resolution.source());
  }

  @Test
  void quarkusFastJarFailsClosedWhenItsApplicationArtifactIsAmbiguous() throws Exception {
    Path distribution = temporaryDirectory.resolve("quarkus-app-ambiguous");
    Path launcher =
        jarAt(
            distribution.resolve("quarkus-run.jar"),
            null,
            "io.quarkus.bootstrap.runner.QuarkusEntryPoint",
            null,
            List.of());
    Path metadata = distribution.resolve("quarkus/quarkus-application.dat");
    Files.createDirectories(metadata.getParent());
    Files.createFile(metadata);
    jarAt(
        distribution.resolve("app/first.jar"),
        null,
        null,
        null,
        List.of("com/acme/first/FirstResource.class"));
    jarAt(
        distribution.resolve("app/second.jar"),
        null,
        null,
        null,
        List.of("org/example/second/SecondResource.class"));

    var resolution = ApplicationPackageResolver.resolve(null, launcher.toString(), "");

    assertTrue(resolution.packagePrefixes().isEmpty());
    assertEquals("none", resolution.source());
  }

  @Test
  void quarkusApplicationClassesFromUnrelatedRootsFailClosed() throws Exception {
    Path distribution = temporaryDirectory.resolve("quarkus-app-unrelated-roots");
    Path launcher =
        jarAt(
            distribution.resolve("quarkus-run.jar"),
            null,
            "io.quarkus.bootstrap.runner.QuarkusEntryPoint",
            null,
            List.of());
    Path metadata = distribution.resolve("quarkus/quarkus-application.dat");
    Files.createDirectories(metadata.getParent());
    Files.createFile(metadata);
    jarAt(
        distribution.resolve("app/application.jar"),
        null,
        null,
        null,
        List.of(
            "com/acme/exchange/ExchangeApplication.class",
            "org/example/extension/UnrelatedExtension.class"));

    var resolution = ApplicationPackageResolver.resolve(null, launcher.toString(), "");

    assertTrue(resolution.packagePrefixes().isEmpty());
    assertEquals("none", resolution.source());
  }

  @Test
  void quarkusLegacyRunnerJarIgnoresGeneratedFrameworkClasses() throws Exception {
    Path lib = temporaryDirectory.resolve("legacy/lib");
    Files.createDirectories(lib);
    Path launcher =
        jarAt(
            temporaryDirectory.resolve("legacy/cambistapp-runner.jar"),
            null,
            "io.quarkus.runner.GeneratedMain",
            "lib/io.quarkus.quarkus-core.jar",
            List.of(
                "io/quarkus/runner/ApplicationImpl.class",
                "org/acme/rates/RatesApplication.class",
                "org/acme/rates/service/RateService.class",
                "org/acme/rates/resource/RateResource.class"));

    var resolution = ApplicationPackageResolver.resolve(null, launcher.toString(), "");

    assertEquals(List.of("org.acme.rates"), resolution.packagePrefixes());
    assertEquals("quarkus-runner-jar", resolution.source());
  }

  @Test
  void quarkusUberJarUsesTheMavenOriginalInsteadOfScanningDependencies() throws Exception {
    Path launcher =
        jarAt(
            temporaryDirectory.resolve("uber/cambistapp-runner.jar"),
            null,
            "io.quarkus.runner.GeneratedMain",
            null,
            List.of(
                "io/quarkus/runner/ApplicationImpl.class",
                "com/acme/exchange/ExchangeApplication.class",
                "org/apache/http/client/HttpClient.class"));
    jarAt(
        temporaryDirectory.resolve("uber/cambistapp.jar.original"),
        null,
        null,
        null,
        List.of(
            "com/acme/exchange/ExchangeApplication.class",
            "com/acme/exchange/service/ExchangeService.class"));

    var resolution = ApplicationPackageResolver.resolve(null, launcher.toString(), "");

    assertEquals(List.of("com.acme.exchange"), resolution.packagePrefixes());
    assertEquals("quarkus-uber-jar", resolution.source());
  }

  @Test
  void quarkusUberJarWithoutItsOriginalApplicationArtifactFailsClosed() throws Exception {
    Path launcher =
        jarAt(
            temporaryDirectory.resolve("incomplete/cambistapp-runner.jar"),
            null,
            "io.quarkus.runner.GeneratedMain",
            null,
            List.of(
                "com/acme/exchange/ExchangeApplication.class",
                "org/apache/http/client/HttpClient.class"));

    var resolution = ApplicationPackageResolver.resolve(null, launcher.toString(), "");

    assertTrue(resolution.packagePrefixes().isEmpty());
    assertEquals("none", resolution.source());
  }

  private Path springBootJar(String startClass) throws Exception {
    return jar(
        "spring boot application.jar",
        startClass,
        "org.springframework.boot.loader.launch.JarLauncher");
  }

  private Path jar(String name, String startClass, String mainClass) throws Exception {
    return jarAt(temporaryDirectory.resolve(name), startClass, mainClass, null, List.of());
  }

  private Path jarAt(
      Path jar,
      String startClass,
      String mainClass,
      String classPath,
      List<String> classEntries)
      throws Exception {
    Files.createDirectories(jar.getParent());
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (startClass != null) {
      attributes.putValue("Start-Class", startClass);
    }
    if (mainClass != null) {
      attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    if (classPath != null) {
      attributes.put(Attributes.Name.CLASS_PATH, classPath);
    }
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      for (String classEntry : classEntries) {
        output.putNextEntry(new JarEntry(classEntry));
        output.closeEntry();
      }
      output.finish();
    }
    return jar;
  }
}
