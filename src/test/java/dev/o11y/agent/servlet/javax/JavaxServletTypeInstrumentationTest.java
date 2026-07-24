package dev.o11y.agent.servlet.javax;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

class JavaxServletTypeInstrumentationTest {
  @Test
  void adviceObservesTheServiceFailure() throws Exception {
    Method exit =
        JavaxServletTypeInstrumentation.ServletAdvice.class.getDeclaredMethod(
            "exit", JavaxServletExchangeHelper.State.class, Throwable.class);

    assertTrue(exit.getParameters()[1].isAnnotationPresent(Advice.Thrown.class));
  }

  @Test
  void thrownServiceFailureUsesTheFailedFallbackInsteadOfTheReportedSuccessStatus() {
    JavaxServletExchangeHelper.State.CompletionOutcome outcome =
        JavaxServletExchangeHelper.State.CompletionOutcome.from(
            new IllegalStateException("service failed"));

    assertEquals(JavaxServletExchangeHelper.State.CompletionOutcome.FAILED, outcome);
    assertEquals(500, outcome.responseStatus(200));
    assertEquals(
        200,
        JavaxServletExchangeHelper.State.CompletionOutcome.from(null).responseStatus(200));
  }

  @Test
  void thrownServiceFailureCompletesImmediatelyInsteadOfWaitingForAsyncSuccess() {
    AtomicBoolean asyncContextRequested = new AtomicBoolean();
    HttpServletRequest request = failingAsyncRequest(asyncContextRequested);
    JavaxServletExchangeHelper.State state =
        JavaxServletExchangeHelper.enter(request, response(new ByteArrayOutputStream()));

    state.exit(new IllegalStateException("service failed"));

    assertFalse(asyncContextRequested.get());
  }

  @Test
  void matchesLegacyServletAndPreservesApplicationBytes() throws Exception {
    JavaxServletTypeInstrumentation instrumentation =
        new JavaxServletTypeInstrumentation();

    assertTrue(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(TestFilter.class)));
    assertTrue(instrumentation.classLoaderOptimization().matches(getClass().getClassLoader()));

    Method filter =
        TestFilter.class.getDeclaredMethod(
            "doFilter", ServletRequest.class, ServletResponse.class, FilterChain.class);
    assertTrue(
        JavaxServletTypeInstrumentation.entrypointMatcher()
            .matches(new MethodDescription.ForLoadedMethod(filter)));

    byte[] body = "{\"amount\":150.50}".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream delivered = new ByteArrayOutputStream();
    CapturingHttpServletResponse response =
        new CapturingHttpServletResponse(response(delivered), 1024);
    CapturingHttpServletRequest request =
        new CapturingHttpServletRequest(request(body), response, 1024);

    assertArrayEquals(body, request.getInputStream().readAllBytes());
    response.getOutputStream().write(body);
    assertArrayEquals(body, request.capturedBody());
    assertArrayEquals(body, response.capturedBody());
    assertArrayEquals(body, delivered.toByteArray());
  }

  private static HttpServletRequest request(byte[] body) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            JavaxServletTypeInstrumentationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getInputStream")) {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                  @Override
                  public int read() {
                    return input.read();
                  }

                  @Override
                  public boolean isFinished() {
                    return input.available() == 0;
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }

                  @Override
                  public void setReadListener(javax.servlet.ReadListener listener) {}
                };
              }
              if (method.getName().equals("getCharacterEncoding")) {
                return "UTF-8";
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static HttpServletRequest failingAsyncRequest(AtomicBoolean asyncContextRequested) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            JavaxServletTypeInstrumentationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getMethod")) {
                return "POST";
              }
              if (method.getName().equals("getRequestURI")) {
                return "/api/failing-async";
              }
              if (method.getName().equals("isAsyncStarted")) {
                return true;
              }
              if (method.getName().equals("getAsyncContext")) {
                asyncContextRequested.set(true);
                return null;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static HttpServletResponse response(ByteArrayOutputStream delivered) {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            JavaxServletTypeInstrumentationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getOutputStream")) {
                return new ServletOutputStream() {
                  @Override
                  public void write(int value) {
                    delivered.write(value);
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }

                  @Override
                  public void setWriteListener(javax.servlet.WriteListener listener) {}
                };
              }
              if (method.getName().equals("getCharacterEncoding")) {
                return "UTF-8";
              }
              if (method.getName().equals("getStatus")) {
                return 200;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }

  private static final class TestFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {}
  }
}
