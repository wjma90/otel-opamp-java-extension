package dev.o11y.agent.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class PackagedExtensionIT {
  private static final List<ServiceContract> CONTRACTS =
      List.of(
          new ServiceContract(
              "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider",
              "dev.o11y.agent.O11yOpampExtension"),
          new ServiceContract(
              "io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizerProvider",
              "dev.o11y.agent.http.DynamicHttpCustomizer"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer",
              "dev.o11y.agent.http.client.spring.SpringRestClientIgnoredTypesConfigurer"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.method.DynamicMethodInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.servlet.DynamicServletInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.servlet.javax.JavaxServletInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.server.quarkus.QuarkusRestInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.spring.SpringRestClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.apache4.ApacheHttpClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.okhttp.OkHttpClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.webclient.SpringWebClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.jdk.JdkHttpClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.http.client.apache5.Apache5HttpClientInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.messaging.kafka.KafkaMessagingInstrumentationModule"),
          new ServiceContract(
              "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
              "dev.o11y.agent.messaging.jms.JmsMessagingInstrumentationModule"));

  @Test
  void shadedJarPublishesLoadableOpenTelemetryExtensionProviders() throws Exception {
    Path jar = Path.of(requiredProperty("packaged.extension.jar")).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(jar), () -> "Packaged extension JAR not found: " + jar);

    verifyPackagedMetadata(jar);
    verifyIsolatedDependencies(jar);
    try (ChildFirstExtensionClassLoader loader =
        new ChildFirstExtensionClassLoader(jar.toUri().toURL(), getClass().getClassLoader())) {
      verifyRelocatedRuntimeLoads(loader);
      for (ServiceContract contract : CONTRACTS) {
        verifyServiceDescriptor(jar, contract);
        verifyProviderLoadsFromJar(jar, loader, contract);
      }
      verifyInstrumentationHelpersArePackaged(jar, loader);
    }
  }

  private static void verifyRelocatedRuntimeLoads(ClassLoader loader) throws Exception {
    Class.forName(
            "dev.o11y.agent.internal.shaded.jackson.databind.ObjectMapper", true, loader)
        .getConstructor()
        .newInstance();
    Class<?> opampClient =
        Class.forName(
            "dev.o11y.agent.internal.shaded.opamp.client.OpampClient", true, loader);
    assertNotNull(opampClient.getMethod("builder").invoke(null));

    Class<?> clientBuilder =
        Class.forName(
            "dev.o11y.agent.internal.shaded.okhttp3.OkHttpClient$Builder", true, loader);
    Object builder = clientBuilder.getConstructor().newInstance();
    Object client = clientBuilder.getMethod("build").invoke(builder);
    Class<?> clientType =
        Class.forName(
            "dev.o11y.agent.internal.shaded.okhttp3.OkHttpClient", true, loader);
    Object dispatcher = clientType.getMethod("dispatcher").invoke(client);
    dispatcher.getClass().getMethod("cancelAll").invoke(dispatcher);
    Object executor = dispatcher.getClass().getMethod("executorService").invoke(dispatcher);
    executor.getClass().getMethod("shutdown").invoke(executor);
    Object connectionPool = clientType.getMethod("connectionPool").invoke(client);
    connectionPool.getClass().getMethod("evictAll").invoke(connectionPool);
  }

  private static void verifyIsolatedDependencies(Path jar) throws IOException {
    Set<String> forbiddenPrefixes =
        Set.of(
            "com/fasterxml/",
            "com/github/f4b6a3/",
            "com/squareup/",
            "io/opentelemetry/api/",
            "io/opentelemetry/context/",
            "io/opentelemetry/instrumentation/api/",
            "io/opentelemetry/javaagent/extension/",
            "io/opentelemetry/opamp/",
            "io/opentelemetry/sdk/",
            "jakarta/servlet/",
            "jakarta/jms/",
            "javax/jms/",
            "javax/servlet/",
            "kotlin/",
            "okhttp3/",
            "okio/",
            "opamp/proto/",
            "org/apache/hc/",
            "org/apache/http/",
            "org/apache/kafka/",
            "org/intellij/",
            "org/jetbrains/",
            "org/reactivestreams/",
            "reactor/",
            "org/springframework/");
    try (JarFile archive = new JarFile(jar.toFile())) {
      List<String> entries = archive.stream().map(entry -> entry.getName()).toList();
      assertTrue(
          entries.stream().noneMatch(PackagedExtensionIT::isSignatureOrModuleDescriptor),
          "The shaded JAR must not retain dependency signatures or module descriptors");
      assertTrue(
          entries.stream()
              .noneMatch(
                  entry -> forbiddenPrefixes.stream().anyMatch(entry::startsWith)),
          "Third-party runtime packages must be relocated away from application namespaces");
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/jackson/databind/ObjectMapper.class"));
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/okhttp3/OkHttpClient.class"));
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/opamp/client/OpampClient.class"));
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/opamp/proto/AgentConfigFile.class"));
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/okio/ByteString.class"));
      assertTrue(
          entries.contains(
              "dev/o11y/agent/internal/shaded/squareup/wire/ProtoAdapter.class"));
      assertTrue(
          entries.stream().noneMatch(entry -> entry.startsWith("dev/o11y/headers/")),
          "The former application runtime must not be packaged alongside the extension");
    }
  }

  @SuppressWarnings("unchecked")
  private static void verifyInstrumentationHelpersArePackaged(Path jar, ClassLoader loader)
      throws Exception {
    List<String> modules =
        List.of(
            "dev.o11y.agent.servlet.DynamicServletInstrumentationModule",
            "dev.o11y.agent.servlet.javax.JavaxServletInstrumentationModule",
            "dev.o11y.agent.http.server.quarkus.QuarkusRestInstrumentationModule",
            "dev.o11y.agent.http.client.spring.SpringRestClientInstrumentationModule",
            "dev.o11y.agent.http.client.apache4.ApacheHttpClientInstrumentationModule",
            "dev.o11y.agent.http.client.okhttp.OkHttpClientInstrumentationModule",
            "dev.o11y.agent.http.client.webclient.SpringWebClientInstrumentationModule",
            "dev.o11y.agent.http.client.jdk.JdkHttpClientInstrumentationModule",
            "dev.o11y.agent.http.client.apache5.Apache5HttpClientInstrumentationModule",
            "dev.o11y.agent.messaging.kafka.KafkaMessagingInstrumentationModule",
            "dev.o11y.agent.messaging.jms.JmsMessagingInstrumentationModule");
    try (JarFile archive = new JarFile(jar.toFile())) {
      for (String moduleName : modules) {
        Class<?> type = Class.forName(moduleName, true, loader);
        Object module = type.getConstructor().newInstance();
        List<String> helperNames =
            (List<String>) type.getMethod("getAdditionalHelperClassNames").invoke(module);
        assertTrue(helperNames.size() >= 20, moduleName + " must declare its helper graph");
        assertTrue(
            helperNames.contains("dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$PolicyCache"),
            moduleName + " must inject the named bounded policy cache");
        assertFalse(
            helperNames.contains("dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$1"),
            moduleName + " must not depend on an unstable anonymous helper name");
        for (String helperName : helperNames) {
          String entry = helperName.replace('.', '/') + ".class";
          assertNotNull(
              archive.getJarEntry(entry), () -> "Declared Java Agent helper is missing: " + entry);
        }
      }
    }
  }

  private static boolean isSignatureOrModuleDescriptor(String entry) {
    String upper = entry.toUpperCase(java.util.Locale.ROOT);
    return upper.equals("MODULE-INFO.CLASS")
        || upper.matches("META-INF/VERSIONS/[^/]+/MODULE-INFO\\.CLASS")
        || upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")
        || upper.matches("META-INF/SIG-[^/]+");
  }

  private static void verifyPackagedMetadata(Path jar) throws IOException {
    try (JarFile archive = new JarFile(jar.toFile())) {
      var entry = archive.getJarEntry("META-INF/o11y-extension.properties");
      assertNotNull(entry, "The filtered extension-version resource must be packaged");
      Properties metadata = new Properties();
      try (InputStream input = archive.getInputStream(entry)) {
        metadata.load(input);
      }
      assertEquals(requiredProperty("expected.extension.version"), metadata.getProperty("version"));
      assertEquals(
          requiredProperty("expected.extension.version"),
          archive.getManifest().getMainAttributes().getValue("Implementation-Version"));
    }
  }

  private static void verifyServiceDescriptor(Path jar, ServiceContract contract)
      throws IOException {
    String descriptor = "META-INF/services/" + contract.serviceType();
    try (JarFile archive = new JarFile(jar.toFile())) {
      var entry = archive.getJarEntry(descriptor);
      assertNotNull(entry, () -> "Missing service descriptor " + descriptor);
      String content;
      try (InputStream input = archive.getInputStream(entry)) {
        content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
      assertTrue(
          content.lines().map(String::trim).anyMatch(contract.providerType()::equals),
          () -> descriptor + " does not register " + contract.providerType());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void verifyProviderLoadsFromJar(
      Path jar, ClassLoader loader, ServiceContract contract) throws Exception {
    Class<?> serviceType = Class.forName(contract.serviceType(), false, loader.getParent());
    ServiceLoader<?> services = ServiceLoader.load((Class) serviceType, loader);
    ServiceLoader.Provider<?> provider =
        services.stream()
            .filter(candidate -> candidate.type().getName().equals(contract.providerType()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "ServiceLoader did not discover " + contract.providerType()));

    Object instance = provider.get();
    assertTrue(serviceType.isInstance(instance));
    assertEquals(
        jar.toUri(),
        provider.type().getProtectionDomain().getCodeSource().getLocation().toURI(),
        "The provider must come from the packaged JAR, not target/classes");
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "").trim();
    assertTrue(!value.isEmpty(), () -> "Missing Maven test property " + name);
    return value;
  }

  private record ServiceContract(String serviceType, String providerType) {}

  private static final class ChildFirstExtensionClassLoader extends URLClassLoader {
    private ChildFirstExtensionClassLoader(URL jar, ClassLoader parent) {
      super(new URL[] {jar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null && name.startsWith("dev.o11y.agent.")) {
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException ignored) {
            // Test helpers are not packaged; delegate those to the test classpath.
          }
        }
        if (loaded == null) {
          loaded = super.loadClass(name, false);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }
  }
}
