package dev.o11y.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.o11y.agent.policy.PolicyState;
import io.opentelemetry.opamp.client.OpampClient;
import io.opentelemetry.opamp.client.internal.response.MessageData;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okio.ByteString;
import opamp.proto.AgentConfigFile;
import opamp.proto.AgentConfigMap;
import opamp.proto.AgentDescription;
import opamp.proto.AgentRemoteConfig;
import opamp.proto.RemoteConfigStatus;
import opamp.proto.RemoteConfigStatuses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class O11yOpampExtensionTest {
  @BeforeEach
  @AfterEach
  void resetPolicyState() throws Exception {
    Method reset = PolicyState.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    reset.invoke(null);
  }

  @Test
  void appliesAValidRemotePolicyAndReportsTheExactConfigHash() {
    RecordingClient client = new RecordingClient();
    ByteString hash = ByteString.encodeUtf8("revision-7");

    new O11yOpampExtension.Callbacks()
        .onMessage(client, message(hash, policySet(validHeaderPolicy())));

    assertEquals(RemoteConfigStatuses.RemoteConfigStatuses_APPLIED, client.status.status);
    assertSame(hash, client.status.last_remote_config_hash);
    assertEquals("", client.status.error_message);
    assertEquals("revision-7", PolicyState.currentSnapshot().revision());
    assertEquals("x-business-channel", System.getProperty(PolicyState.REQUEST_HEADERS_PROPERTY));
  }

  @Test
  void rejectsTheWholeRemoteSnapshotAndKeepsThePreviouslyAppliedGeneration() {
    RecordingClient client = new RecordingClient();
    O11yOpampExtension.Callbacks callbacks = new O11yOpampExtension.Callbacks();
    callbacks.onMessage(
        client,
        message(ByteString.encodeUtf8("accepted"), policySet(validHeaderPolicy())));
    PolicyState.Snapshot accepted = PolicyState.currentSnapshot();

    callbacks.onMessage(
        client,
        message(
            ByteString.encodeUtf8("rejected"),
            policySet(validHeaderPolicy().replace("\"schemaVersion\"", "\"unknown\""))));

    assertEquals(RemoteConfigStatuses.RemoteConfigStatuses_FAILED, client.status.status);
    assertFalse(client.status.error_message.isBlank());
    assertEquals(accepted.generation(), PolicyState.currentSnapshot().generation());
    assertEquals("x-business-channel", System.getProperty(PolicyState.REQUEST_HEADERS_PROPERTY));
  }

  @Test
  void rejectsOversizedRemoteConfigBeforeParsingIt() {
    RecordingClient client = new RecordingClient();

    new O11yOpampExtension.Callbacks()
        .onMessage(
            client,
            message(ByteString.encodeUtf8("oversized"), " ".repeat(1024 * 1024 + 1)));

    assertEquals(RemoteConfigStatuses.RemoteConfigStatuses_FAILED, client.status.status);
    assertEquals(
        "remote policy exceeds the 1048576-byte safety limit",
        client.status.error_message);
  }

  @Test
  void validatesOpampTransportConfigurationWithoutLeakingSecretsIntoUrls() {
    assertEquals(
        "https://control-plane.o11y.svc.cluster.local:4320/v1/opamp",
        O11yOpampExtension.validatedEndpoint(
            "https://control-plane.o11y.svc.cluster.local:4320/v1/opamp"));
    assertEquals("", O11yOpampExtension.validatedToken(""));
    assertEquals("opaque-token", O11yOpampExtension.validatedToken("opaque-token"));
    assertEquals(
        "exchange-service",
        O11yOpampExtension.validatedServiceName(" exchange-service "));

    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.validatedEndpoint("ftp://control-plane/v1/opamp"));
    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.validatedEndpoint("https://user:secret@control-plane/v1/opamp"));
    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.validatedEndpoint("https://control-plane/v1/opamp?token=secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.validatedToken("token\nheader"));
    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.validatedServiceName("service\r\nheader"));
  }

  @Test
  void addsAPemCaToTheJvmTrustWithoutDisablingHostnameVerification() throws Exception {
    Path caFile =
        Path.of(
            O11yOpampExtensionTest.class
                .getResource("/tls/opamp-test-ca.crt")
                .toURI());
    X509TrustManager trustManager = O11yOpampExtension.opampTrustManager(caFile);

    assertEquals(
        1,
        List.of(trustManager.getAcceptedIssuers()).stream()
            .filter(
                certificate ->
                    certificate
                        .getSubjectX500Principal()
                        .getName()
                        .contains("CN=o11y-opamp-test-ca"))
            .count());
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    assertSame(
        builder,
        O11yOpampExtension.configureOpampTls(
            builder, "https://control-plane:4320/v1/opamp", ""));
  }

  @Test
  void rejectsCustomCaForHttpAndMissingFiles() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            O11yOpampExtension.configureOpampTls(
                new OkHttpClient.Builder(),
                "http://control-plane:4320/v1/opamp",
                "/etc/o11y/opamp-ca/ca.crt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> O11yOpampExtension.opampTrustManager(Path.of("missing-opamp-ca.crt")));
  }

  @Test
  void reportsSuccessfulPollAtDebugLevel() {
    Logger logger = Logger.getLogger(O11yOpampExtension.class.getName());
    Level previousLevel = logger.getLevel();
    boolean previousUseParentHandlers = logger.getUseParentHandlers();
    List<LogRecord> records = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setLevel(Level.ALL);

    try {
      logger.setUseParentHandlers(false);
      logger.setLevel(Level.ALL);
      logger.addHandler(handler);

      new O11yOpampExtension.Callbacks().onConnect(new RecordingClient());

      assertEquals(1, records.size());
      assertEquals(Level.FINE, records.getFirst().getLevel());
      assertEquals(
          "o11y_opamp=online transport=http-poll",
          records.getFirst().getMessage());
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousUseParentHandlers);
    }
  }

  private static MessageData message(ByteString hash, String policy) {
    AgentConfigFile file =
        new AgentConfigFile(ByteString.encodeUtf8(policy), "application/json");
    AgentConfigMap config =
        new AgentConfigMap(Map.of("dev.o11y/http-headers.json", file));
    AgentRemoteConfig remoteConfig = new AgentRemoteConfig(config, hash);
    return MessageData.builder().setRemoteConfig(remoteConfig).build();
  }

  private static String policySet(String policy) {
    return """
        {
          "apiVersion": "o11y.dev/v1",
          "kind": "PolicySet",
          "revision": "revision-7",
          "policies": [{
            "id": "http-capture",
            "version": 7,
            "policy": %s
          }]
        }
        """.formatted(policy);
  }

  private static String validHeaderPolicy() {
    return """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [{"name": "X-Business-Channel"}]
        }
        """;
  }

  private static final class RecordingClient implements OpampClient {
    private RemoteConfigStatus status;

    @Override
    public void setAgentDescription(AgentDescription ignored) {}

    @Override
    public void setRemoteConfigStatus(RemoteConfigStatus status) {
      this.status = status;
    }

    @Override
    public void close() {}
  }
}
