package dev.o11y.agent;

import dev.o11y.agent.method.discovery.ApplicationPackageResolver;
import dev.o11y.agent.policy.DynamicPolicy;
import dev.o11y.agent.policy.PolicyState;
import dev.o11y.agent.policy.PolicyValidator;
import io.opentelemetry.opamp.client.OpampClient;
import io.opentelemetry.opamp.client.OpampClientBuilder;
import io.opentelemetry.opamp.client.internal.connectivity.http.OkHttpSender;
import io.opentelemetry.opamp.client.internal.request.delay.PeriodicDelay;
import io.opentelemetry.opamp.client.internal.request.delay.RetryPeriodicDelay;
import io.opentelemetry.opamp.client.internal.request.service.HttpRequestService;
import io.opentelemetry.opamp.client.internal.response.MessageData;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import opamp.proto.AgentConfigFile;
import opamp.proto.AgentRemoteConfig;
import opamp.proto.RemoteConfigStatus;
import opamp.proto.RemoteConfigStatuses;
import opamp.proto.ServerErrorResponse;

public final class O11yOpampExtension implements AutoConfigurationCustomizerProvider {
  private static final String OPAMP_TLS_PROTOCOL = "TLSv1.3";
  private static final Logger LOGGER = Logger.getLogger(O11yOpampExtension.class.getName());
  private static final int MAX_REMOTE_CONFIG_BYTES = 1024 * 1024;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
  private static final long MAX_OPAMP_CA_BYTES = 1024 * 1024;

  private static volatile RuntimeState runtime;
  private static boolean initializationAttempted;

  @Override
  public void customize(AutoConfigurationCustomizer ignored) {
    start();
  }

  private static synchronized void start() {
    if (initializationAttempted) {
      return;
    }
    initializationAttempted = true;

    OkHttpClient httpClient = null;
    try {
      String endpoint = validatedEndpoint(env("OPAMP_ENDPOINT", "http://localhost:4320/v1/opamp"));
      String token = validatedToken(env("OPAMP_TOKEN", ""));
      String service = validatedServiceName(env("OTEL_SERVICE_NAME", "java-service"));
      int pollIntervalSeconds =
          boundedIntegerEnv("OPAMP_POLL_INTERVAL_SECONDS", 10, 2, 300);

      OkHttpClient.Builder httpClientBuilder =
          new OkHttpClient.Builder()
              .connectTimeout(CONNECT_TIMEOUT)
              .readTimeout(READ_TIMEOUT)
              .writeTimeout(WRITE_TIMEOUT)
              .callTimeout(CALL_TIMEOUT)
              .retryOnConnectionFailure(true);
      configureOpampTls(
          httpClientBuilder, endpoint, env("OPAMP_TLS_CA_FILE", ""));
      httpClient =
          httpClientBuilder
              .addInterceptor(
                  chain -> {
                    var request =
                        chain
                            .request()
                            .newBuilder()
                            .header("X-Service-Name", service)
                            .header("X-O11y-Transport", "http-poll")
                            .header(
                                "X-O11y-Poll-Interval-Seconds",
                                String.valueOf(pollIntervalSeconds));
                    if (!token.isEmpty()) {
                      request.header("Authorization", "Bearer " + token);
                    }
                    return chain.proceed(request.build());
                  })
              .build();

      PeriodicDelay pollDelay =
          PeriodicDelay.ofFixedDuration(Duration.ofSeconds(pollIntervalSeconds));
      PeriodicDelay retryDelay =
          RetryPeriodicDelay.create(Duration.ofSeconds(pollIntervalSeconds));

      OpampClientBuilder builder =
          OpampClient.builder()
              .setRequestService(
                  HttpRequestService.create(
                      OkHttpSender.create(endpoint, httpClient), pollDelay, retryDelay))
              .putIdentifyingAttribute("service.name", service)
              .putIdentifyingAttribute("agent.type", "java-extension")
              .putNonIdentifyingAttribute("telemetry.sdk.language", "java")
              .putNonIdentifyingAttribute(
                  "o11y.policy.schema", PolicyValidator.MAX_SUPPORTED_SCHEMA_VERSION)
              .putNonIdentifyingAttribute("o11y.agent.extension.version", extensionVersion())
              .putNonIdentifyingAttribute("o11y.opamp.transport", "http-poll")
              .putNonIdentifyingAttribute(
                  "o11y.opamp.poll_interval_seconds",
                  String.valueOf(pollIntervalSeconds))
              .putNonIdentifyingAttribute(
                  "o11y.method.packages",
                  String.join(",", PolicyValidator.allowedPackages()))
              .putNonIdentifyingAttribute(
                  "o11y.method.packages.source", ApplicationPackageResolver.source())
              .enableRemoteConfig();
      addResourceAttributes(builder);

      RuntimeState started = new RuntimeState(builder.build(new Callbacks()), httpClient);
      runtime = started;
      try {
        Runtime.getRuntime()
            .addShutdownHook(new Thread(O11yOpampExtension::stop, "o11y-opamp-stop"));
      } catch (RuntimeException error) {
        runtime = null;
        started.close();
        throw error;
      }
      LOGGER.log(
          Level.INFO,
          () ->
              "o11y_opamp=started transport=http-poll endpoint="
                  + endpoint
                  + " poll_interval_seconds="
                  + pollIntervalSeconds);
    } catch (RuntimeException error) {
      closeHttpClient(httpClient);
      LOGGER.log(
          Level.SEVERE,
          () -> "o11y_opamp=disabled reason=" + safeLogValue(safeError(error)));
    }
  }

  private static synchronized void stop() {
    RuntimeState running = runtime;
    runtime = null;
    if (running != null) {
      running.close();
    }
  }

  private static String extensionVersion() {
    Properties metadata = new Properties();
    try (InputStream input =
        O11yOpampExtension.class.getResourceAsStream(
            "/META-INF/o11y-extension.properties")) {
      if (input != null) {
        metadata.load(input);
        String version = metadata.getProperty("version", "").trim();
        if (!version.isEmpty()) {
          return version;
        }
      }
    } catch (IOException ignored) {
      // Fall through to the manifest metadata for development builds.
    }
    String version = O11yOpampExtension.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  static final class Callbacks implements OpampClient.Callbacks {
    @Override
    public void onConnect(OpampClient client) {
      LOGGER.log(Level.FINE, "o11y_opamp=online transport=http-poll");
    }

    @Override
    public void onConnectFailed(OpampClient client, Throwable error) {
      LOGGER.log(
          Level.WARNING,
          () ->
              "o11y_opamp=poll_failed transport=http-poll error="
                  + safeLogValue(safeError(error)));
    }

    @Override
    public void onErrorResponse(OpampClient client, ServerErrorResponse error) {
      LOGGER.log(
          Level.WARNING,
          () ->
              "o11y_opamp=server_error transport=http-poll error="
                  + safeLogValue(String.valueOf(error)));
    }

    @Override
    public void onMessage(OpampClient client, MessageData message) {
      AgentRemoteConfig remoteConfig = message.getRemoteConfig();
      if (remoteConfig == null || remoteConfig.config == null) {
        return;
      }

      AgentConfigFile configFile =
          remoteConfig.config.config_map.get("dev.o11y/http-headers.json");
      if (configFile == null) {
        return;
      }

      boolean valid = false;
      String errorMessage = "";
      try {
        if (configFile.body.size() > MAX_REMOTE_CONFIG_BYTES) {
          throw new IllegalArgumentException(
              "remote policy exceeds the 1048576-byte safety limit");
        }
        String json = new String(configFile.body.toByteArray(), StandardCharsets.UTF_8);
        PolicyState.Snapshot snapshot = PolicyState.applyJson(json);
        DynamicPolicy policy = snapshot.effectivePolicy();
        String incomingRequestHeaders = names(policy.requestHeaders(), "INCOMING");
        String incomingResponseHeaders = names(policy.responseHeaders(), "INCOMING");
        String outgoingRequestHeaders = names(policy.requestHeaders(), "OUTGOING");
        String outgoingResponseHeaders = names(policy.responseHeaders(), "OUTGOING");
        valid = true;
        LOGGER.log(
            Level.INFO,
            () ->
                "o11y_opamp=policy_set_applied generation="
                    + snapshot.generation()
                    + " policies="
                    + snapshot.policies().size()
                    + " incoming_request_headers="
                    + incomingRequestHeaders
                    + " incoming_response_headers="
                    + incomingResponseHeaders
                    + " outgoing_request_headers="
                    + outgoingRequestHeaders
                    + " outgoing_response_headers="
                    + outgoingResponseHeaders
                    + " http_metrics="
                    + policy.metricPolicies().size()
                    + " method_policies="
                    + policy.methodPolicies().size()
                    + " body_events="
                    + policy.bodyEventPolicies().size()
                    + " event_metrics="
                    + policy.eventMetricPolicies().size());
      } catch (Exception error) {
        errorMessage = truncate(error.getMessage());
        String loggedError = safeLogValue(errorMessage);
        LOGGER.log(
            Level.WARNING,
            () -> "o11y_opamp=policy_rejected reason=" + loggedError);
      }

      RemoteConfigStatus status =
          new RemoteConfigStatus.Builder()
              .last_remote_config_hash(remoteConfig.config_hash)
              .status(
                  valid
                      ? RemoteConfigStatuses.RemoteConfigStatuses_APPLIED
                      : RemoteConfigStatuses.RemoteConfigStatuses_FAILED)
              .error_message(
                  valid ? "" : errorMessage)
              .build();
      client.setRemoteConfigStatus(status);
    }
  }

  static String extractNames(String json, String section) {
    Matcher block =
        Pattern.compile(
                "\\\"" + Pattern.quote(section) + "\\\"\\s*:\\s*\\[(.*?)]",
                Pattern.DOTALL)
            .matcher(json);
    if (!block.find()) {
      return "";
    }

    Matcher names =
        Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([A-Za-z0-9_.-]+)\\\"")
            .matcher(block.group(1));
    StringBuilder output = new StringBuilder();

    while (names.find()) {
      String name = names.group(1).toLowerCase(Locale.ROOT);
      if (output.length() < 1000) {
        if (!output.isEmpty()) {
          output.append(',');
        }
        output.append(name);
      }
    }

    return output.toString();
  }

  static String env(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  static String validatedEndpoint(String value) {
    try {
      URI endpoint = new URI(value);
      String scheme = endpoint.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          || endpoint.getHost() == null
          || endpoint.getHost().isBlank()
          || endpoint.getUserInfo() != null
          || endpoint.getQuery() != null
          || endpoint.getFragment() != null) {
        throw new IllegalArgumentException(
            "OPAMP_ENDPOINT must be an absolute HTTP(S) URL without credentials, query or fragment");
      }
      return endpoint.toASCIIString();
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("OPAMP_ENDPOINT is not a valid URI", error);
    }
  }

  static OkHttpClient.Builder configureOpampTls(
      OkHttpClient.Builder builder, String endpoint, String caFile) {
    String configuredCA = caFile == null ? "" : caFile.trim();
    if (configuredCA.isEmpty()) {
      return builder;
    }
    if (!URI.create(endpoint).getScheme().equalsIgnoreCase("https")) {
      throw new IllegalArgumentException(
          "OPAMP_TLS_CA_FILE requires an HTTPS OPAMP_ENDPOINT");
    }
    X509TrustManager trustManager = opampTrustManager(Path.of(configuredCA));
    try {
      SSLContext sslContext = SSLContext.getInstance(OPAMP_TLS_PROTOCOL);
      sslContext.init(null, new TrustManager[] {trustManager}, null);
      return builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("cannot initialize OpAMP TLS", error);
    }
  }

  static X509TrustManager opampTrustManager(Path caFile) {
    try {
      if (!Files.isRegularFile(caFile)) {
        throw new IllegalArgumentException("OPAMP_TLS_CA_FILE must reference a regular file");
      }
      long size = Files.size(caFile);
      if (size <= 0 || size > MAX_OPAMP_CA_BYTES) {
        throw new IllegalArgumentException(
            "OPAMP_TLS_CA_FILE must contain 1 to 1048576 bytes");
      }
      Collection<? extends Certificate> certificates;
      try (InputStream input = Files.newInputStream(caFile)) {
        certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
      }
      if (certificates.isEmpty()
          || certificates.stream().anyMatch(certificate -> !(certificate instanceof X509Certificate))) {
        throw new IllegalArgumentException(
            "OPAMP_TLS_CA_FILE must contain at least one PEM X.509 certificate");
      }

      KeyStore customStore = KeyStore.getInstance(KeyStore.getDefaultType());
      customStore.load(null);
      int index = 0;
      for (Certificate certificate : certificates) {
        customStore.setCertificateEntry("opamp-ca-" + index++, certificate);
      }
      return compositeTrustManager(
          x509TrustManager(null), x509TrustManager(customStore));
    } catch (IOException | GeneralSecurityException error) {
      throw new IllegalArgumentException("cannot load OPAMP_TLS_CA_FILE", error);
    }
  }

  private static X509TrustManager x509TrustManager(KeyStore keyStore)
      throws GeneralSecurityException {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(keyStore);
    for (TrustManager manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509TrustManager) {
        return x509TrustManager;
      }
    }
    throw new GeneralSecurityException("no X.509 trust manager is available");
  }

  private static X509TrustManager compositeTrustManager(X509TrustManager... delegates) {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        checkTrusted(chain, authType, false);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        checkTrusted(chain, authType, true);
      }

      private void checkTrusted(
          X509Certificate[] chain, String authType, boolean server)
          throws CertificateException {
        CertificateException rejection = null;
        for (X509TrustManager delegate : delegates) {
          try {
            if (server) {
              delegate.checkServerTrusted(chain, authType);
            } else {
              delegate.checkClientTrusted(chain, authType);
            }
            return;
          } catch (CertificateException error) {
            rejection = error;
          }
        }
        throw rejection == null
            ? new CertificateException("no X.509 trust manager is configured")
            : rejection;
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        List<X509Certificate> issuers = new ArrayList<>();
        for (X509TrustManager delegate : delegates) {
          issuers.addAll(List.of(delegate.getAcceptedIssuers()));
        }
        return issuers.toArray(X509Certificate[]::new);
      }
    };
  }

  static String validatedToken(String value) {
    if (value.length() > 4096
        || value.chars().anyMatch(character -> character <= 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException(
          "OPAMP_TOKEN must contain at most 4096 visible ASCII characters");
    }
    return value;
  }

  static String validatedServiceName(String value) {
    String service = value.trim();
    if (service.isEmpty()
        || service.length() > 255
        || service.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException(
          "OTEL_SERVICE_NAME must contain 1 to 255 printable ASCII characters");
    }
    return service;
  }

  private static int boundedIntegerEnv(
      String key,
      int defaultValue,
      int minimum,
      int maximum) {
    try {
      int value = Integer.parseInt(env(key, String.valueOf(defaultValue)));
      return Math.max(minimum, Math.min(maximum, value));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private static String names(List<DynamicPolicy.NamedValue> values, String direction) {
    return values.stream()
        .filter(value -> direction.equalsIgnoreCase(value.direction))
        .map(value -> value.name.toLowerCase(Locale.ROOT).trim())
        .filter(value -> value.matches("[a-z0-9_.!#$%&'*+^`|~-]+"))
        .distinct()
        .limit(64)
        .reduce((left, right) -> left + "," + right)
        .orElse("");
  }

  private static void addResourceAttributes(OpampClientBuilder builder) {
    resourceAttributes().forEach(builder::putNonIdentifyingAttribute);
  }

  private static Map<String, String> resourceAttributes() {
    Map<String, String> attributes = new LinkedHashMap<>();
    String configured = env("OTEL_RESOURCE_ATTRIBUTES", "");
    for (String entry : configured.split(",")) {
      if (attributes.size() >= 64) {
        break;
      }
      int separator = entry.indexOf('=');
      if (separator <= 0 || separator == entry.length() - 1) {
        continue;
      }
      String key = entry.substring(0, separator).trim();
      String value = entry.substring(separator + 1).trim();
      if (key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,80}") && value.length() <= 128) {
        attributes.put(key, value);
      }
    }
    putIfPresent(attributes, "service.version", System.getenv("OTEL_SERVICE_VERSION"));
    putIfPresent(
        attributes,
        "deployment.environment.name",
        System.getenv("OTEL_DEPLOYMENT_ENVIRONMENT"));
    return attributes;
  }

  private static void putIfPresent(
      Map<String, String> attributes, String key, String value) {
    if (value != null && !value.isBlank() && value.length() <= 128) {
      attributes.put(key, value);
    }
  }

  private static String truncate(String value) {
    if (value == null || value.isBlank()) {
      return "invalid dynamic policy";
    }
    return value.substring(0, Math.min(value.length(), 512));
  }

  private static String safeLogValue(String value) {
    return truncate(value).replaceAll("\\s+", "_");
  }

  private static String safeError(Throwable error) {
    if (error == null) {
      return "unknown";
    }
    String message = truncate(error.getMessage()).replaceAll("\\s+", "_");
    return error.getClass().getSimpleName() + ":" + message;
  }

  private static void closeHttpClient(OkHttpClient httpClient) {
    if (httpClient == null) {
      return;
    }
    httpClient.dispatcher().cancelAll();
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
    Cache cache = httpClient.cache();
    if (cache != null) {
      try {
        cache.close();
      } catch (IOException error) {
        LOGGER.log(
            Level.FINE,
            () -> "o11y_opamp=http_cache_close_failed error=" + safeLogValue(safeError(error)));
      }
    }
  }

  private record RuntimeState(OpampClient client, OkHttpClient httpClient) {
    private void close() {
      try {
        client.close();
      } catch (IOException | RuntimeException error) {
        LOGGER.log(
            Level.FINE,
            () -> "o11y_opamp=client_close_failed error=" + safeLogValue(safeError(error)));
      } finally {
        closeHttpClient(httpClient);
      }
    }
  }
}
