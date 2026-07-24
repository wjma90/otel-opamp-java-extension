package dev.o11y.agent.http.client.okhttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.http.client.OutgoingHttpExchange;
import dev.o11y.agent.http.runtime.HttpBodyPolicyEngine;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OkHttpClientBridgeTest {
  @AfterEach
  void clearPolicy() {
    System.clearProperty(HttpBodyPolicyEngine.POLICY_PROPERTY);
    System.clearProperty("o11y.dynamic.policy.active-generation");
  }

  @Test
  void installsExactlyOneApplicationInterceptorPerBuilder() throws Exception {
    Class<?> builderType = Class.forName(OkHttpClientBridge.builderClassName());
    Object builder = builderType.getConstructor().newInstance();

    OkHttpClientBridge.install(builder);
    OkHttpClientBridge.install(builder);

    Method configuredInterceptors = builderType.getMethod("interceptors");
    List<?> interceptors = (List<?>) configuredInterceptors.invoke(builder);
    assertEquals(1, interceptors.size());
    assertTrue(Proxy.isProxyClass(interceptors.getFirst().getClass()));
    assertEquals("o11y outgoing application interceptor", interceptors.getFirst().toString());
  }

  @Test
  void matchesTheApplicationBuilderAndRequestBodyWithoutLinkingTheirTypes() throws Exception {
    Class<?> clientType = Class.forName(OkHttpClientBridge.clientClassName());
    Class<?> builderType = Class.forName(OkHttpClientBridge.builderClassName());
    Class<?> requestBodyType = Class.forName(OkHttpClientBridge.requestBodyClassName());

    OkHttpClientTypeInstrumentation clientInstrumentation =
        new OkHttpClientTypeInstrumentation();
    OkHttpClientBuilderTypeInstrumentation builderInstrumentation =
        new OkHttpClientBuilderTypeInstrumentation();
    OkHttpRequestBodyTypeInstrumentation bodyInstrumentation =
        new OkHttpRequestBodyTypeInstrumentation();

    assertTrue(
        clientInstrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(clientType)));
    assertTrue(
        builderInstrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(builderType)));
    assertTrue(
        bodyInstrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(requestBodyType)));
    assertTrue(
        clientInstrumentation.classLoaderOptimization().matches(clientType.getClassLoader()));
    assertTrue(
        builderInstrumentation.classLoaderOptimization().matches(builderType.getClassLoader()));
    assertTrue(
        bodyInstrumentation.classLoaderOptimization().matches(requestBodyType.getClassLoader()));
  }

  @Test
  void moduleCoversTheClientConstructorAndBuilderBuildPaths() {
    OkHttpClientInstrumentationModule module = new OkHttpClientInstrumentationModule();

    assertTrue(
        module.typeInstrumentations().stream()
            .anyMatch(OkHttpClientTypeInstrumentation.class::isInstance));
    assertTrue(
        module.typeInstrumentations().stream()
            .anyMatch(OkHttpClientBuilderTypeInstrumentation.class::isInstance));
  }

  @Test
  void exposesAUniqueAndCompleteHelperSet() {
    List<String> builderHelpers =
        OkHttpClientBuilderTypeInstrumentation.helperClassNames();
    List<String> bodyHelpers = OkHttpRequestBodyTypeInstrumentation.helperClassNames();

    assertEquals(builderHelpers, bodyHelpers);
    assertEquals(builderHelpers.size(), new HashSet<>(builderHelpers).size());
    assertTrue(builderHelpers.contains(OkHttpClientBridge.class.getName()));
    assertTrue(builderHelpers.stream().anyMatch(name -> name.endsWith("$KnownApplicationType")));
    assertTrue(builderHelpers.stream().anyMatch(name -> name.endsWith("$RequestWriteState")));
    assertTrue(
        builderHelpers.stream().anyMatch(name -> name.endsWith("$ApplicationInterceptorHandler")));
    assertTrue(builderHelpers.stream().anyMatch(name -> name.endsWith("$SinkCaptureHandler")));
    assertTrue(builderHelpers.stream().anyMatch(name -> name.endsWith("$SourceCaptureHandler")));
    assertFalse(
        OkHttpClientBridge.builderClassName().startsWith(OkHttpClientBridge.internalShadedPrefix()));
  }

  @Test
  void boundsStringsByteStringsAndBuffersBeforeAllocatingCaptureCopies() throws Exception {
    Method capture =
        OkHttpClientBridge.class.getDeclaredMethod(
            "bytesBeforeInvocation", String.class, Object[].class, int.class);
    capture.setAccessible(true);
    String large = "x".repeat(1_000_000);

    byte[] stringPrefix =
        (byte[]) capture.invoke(null, "writeUtf8", new Object[] {large}, 17);
    assertEquals(17, stringPrefix.length);

    Class<?> byteStringType = Class.forName("okio.ByteString");
    Object byteString = byteStringType.getMethod("encodeUtf8", String.class).invoke(null, large);
    byte[] byteStringPrefix =
        (byte[]) capture.invoke(null, "write", new Object[] {byteString}, 19);
    assertEquals(19, byteStringPrefix.length);

    Class<?> bufferType = Class.forName("okio.Buffer");
    Object buffer = bufferType.getConstructor().newInstance();
    bufferType.getMethod("writeUtf8", String.class).invoke(buffer, large);
    byte[] bufferPrefix =
        (byte[]) capture.invoke(null, "write", new Object[] {buffer, 1_000_000L}, 23);
    assertEquals(23, bufferPrefix.length);
    assertEquals(1_000_000L, bufferType.getMethod("size").invoke(buffer));
  }

  @Test
  void teesSerializedBytesWithoutChangingTheApplicationSink() throws Exception {
    dev.o11y.agent.policy.PolicyState.applyJson(resource("http-outgoing-rates-client.json"));
    OutgoingHttpExchange exchange =
        OutgoingHttpExchange.start(
            "POST",
            "https://example.test/api/rates/quote",
            Map.of("content-type", List.of("application/json")));
    assertTrue(exchange.isOwner());

    Class<?> activeCallType =
        Class.forName(OkHttpClientBridge.class.getName() + "$ActiveCall");
    Constructor<?> constructor =
        activeCallType.getDeclaredConstructor(OutgoingHttpExchange.class, Object.class);
    constructor.setAccessible(true);
    byte[] json = "{\"amount\":150.5}".getBytes(StandardCharsets.UTF_8);
    Class<?> requestBodyType = Class.forName(OkHttpClientBridge.requestBodyClassName());
    Method createBody = requestBodyType.getMethod("create", byte[].class);
    Object requestBody = createBody.invoke(null, (Object) json);
    Object activeCall = constructor.newInstance(exchange, requestBody);
    Method push = OkHttpClientBridge.class.getDeclaredMethod("push", activeCallType);
    Method pop = OkHttpClientBridge.class.getDeclaredMethod("pop", activeCallType);
    push.setAccessible(true);
    pop.setAccessible(true);

    Class<?> bufferType = Class.forName("okio.Buffer");
    Object applicationBuffer = bufferType.getConstructor().newInstance();
    Object wrapped;
    push.invoke(null, activeCall);
    try {
      OkHttpClientBridge.RequestWriteState first =
          OkHttpClientBridge.beginRequestWrite(requestBody, applicationBuffer);
      wrapped = first.sink();
      Class<?> sinkType = Class.forName(OkHttpClientBridge.bufferedSinkClassName());
      requestBodyType.getMethod("writeTo", sinkType).invoke(requestBody, wrapped);
      Object sourceBuffer = bufferType.getConstructor().newInstance();
      bufferType.getMethod("writeUtf8", String.class).invoke(sourceBuffer, "-tail");
      sinkType.getMethod("write", bufferType, long.class).invoke(wrapped, sourceBuffer, 5L);
      OkHttpClientBridge.finishRequestWrite(first, new java.io.IOException("retry"));

      Object retryBuffer = bufferType.getConstructor().newInstance();
      OkHttpClientBridge.RequestWriteState retry =
          OkHttpClientBridge.beginRequestWrite(requestBody, retryBuffer);
      Object retrySink = retry.sink();
      requestBodyType.getMethod("writeTo", sinkType).invoke(requestBody, retrySink);
      OkHttpClientBridge.finishRequestWrite(retry, null);
      assertEquals("{\"amount\":150.5}", bufferType.getMethod("readUtf8").invoke(retryBuffer));
    } finally {
      pop.invoke(null, activeCall);
    }

    assertEquals(
        "{\"amount\":150.5}-tail", bufferType.getMethod("readUtf8").invoke(applicationBuffer));
    assertEquals("{\"amount\":150.5}", capturedRequest(exchange));
    exchange.abort();
  }

  private static String capturedRequest(OutgoingHttpExchange exchange) throws Exception {
    Field body = OutgoingHttpExchange.class.getDeclaredField("requestBody");
    body.setAccessible(true);
    Object capture = body.get(exchange);
    Method bytes = capture.getClass().getDeclaredMethod("bytes");
    bytes.setAccessible(true);
    return new String((byte[]) bytes.invoke(capture), StandardCharsets.UTF_8);
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/policies/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
