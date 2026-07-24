package dev.o11y.agent.http.server.quarkus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.o11y.agent.servlet.BoundedBodyCapture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuarkusRestInstrumentationModuleTest {
  @AfterEach
  void clearProperties() {
    System.clearProperty("o11y.dynamic.request.headers");
  }

  @Test
  void registersOnlyTheQuarkusRestContextAndItsBoundedHelperGraph() {
    QuarkusRestInstrumentationModule module = new QuarkusRestInstrumentationModule();

    assertEquals(1, module.typeInstrumentations().size());
    assertTrue(
        module.getAdditionalHelperClassNames().contains(QuarkusRestExchangeHelper.class.getName()));
    assertTrue(
        module.getAdditionalHelperClassNames().contains(QuarkusCapturingInputStream.class.getName()));
    assertTrue(
        module.getAdditionalHelperClassNames().contains(QuarkusCapturingOutputStream.class.getName()));
  }

  @Test
  void responseAdviceObservesFailuresAndCompletesOnlyEndMethods() throws Exception {
    Method exit =
        QuarkusRestTypeInstrumentation.ResponseAdvice.class.getDeclaredMethod(
            "exit", Object.class, String.class, Throwable.class);

    assertTrue(exit.getParameters()[2].isAnnotationPresent(Advice.Thrown.class));
  }

  @Test
  void streamWrappersPreserveEveryApplicationByteWhileCapturingABoundedCopy()
      throws Exception {
    byte[] body = "{\"marker\":\"intact\"}".getBytes(StandardCharsets.UTF_8);
    BoundedBodyCapture inputCapture = new BoundedBodyCapture(1024);
    QuarkusCapturingInputStream input =
        new QuarkusCapturingInputStream(new ByteArrayInputStream(body), inputCapture);

    assertArrayEquals(body, input.readAllBytes());
    assertArrayEquals(body, inputCapture.bytes());

    BoundedBodyCapture outputCapture = new BoundedBodyCapture(1024);
    ByteArrayOutputStream delivered = new ByteArrayOutputStream();
    QuarkusCapturingOutputStream output =
        new QuarkusCapturingOutputStream(new Object(), delivered, outputCapture);
    output.write(body);
    output.close();

    assertArrayEquals(body, delivered.toByteArray());
    assertArrayEquals(body, outputCapture.bytes());
  }

  @Test
  void missingCloseHandlerFailsClosedWithoutRetainingExchangeState() {
    System.setProperty("o11y.dynamic.request.headers", "x-test");

    QuarkusRestExchangeHelper.start(new ExchangeWithoutCloseHandler());

    assertEquals(0, QuarkusRestExchangeHelper.activeExchangeCountForTest());
  }

  static final class ExchangeWithoutCloseHandler {
    public String getRequestMethod() {
      return "GET";
    }

    public String getRequestPath() {
      return "/healthz";
    }

    public String getRequestHeader(String ignored) {
      return "";
    }

    public String query() {
      return "";
    }
  }
}
