package dev.o11y.agent.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

class DynamicServletTypeInstrumentationTest {
  @Test
  void adviceObservesTheServiceFailure() throws Exception {
    Method exit =
        DynamicServletTypeInstrumentation.ServletAdvice.class.getDeclaredMethod(
            "exit", ServletExchangeHelper.State.class, Throwable.class);

    assertTrue(exit.getParameters()[1].isAnnotationPresent(Advice.Thrown.class));
  }

  @Test
  void thrownServiceFailureUsesTheFailedFallbackInsteadOfTheReportedSuccessStatus() {
    ServletExchangeHelper.State.CompletionOutcome outcome =
        ServletExchangeHelper.State.CompletionOutcome.from(
            new IllegalStateException("service failed"));

    assertEquals(ServletExchangeHelper.State.CompletionOutcome.FAILED, outcome);
    assertEquals(500, outcome.responseStatus(200));
    assertEquals(
        200, ServletExchangeHelper.State.CompletionOutcome.from(null).responseStatus(200));
  }

  @Test
  void thrownServiceFailureCompletesImmediatelyInsteadOfWaitingForAsyncSuccess() {
    AtomicBoolean asyncContextRequested = new AtomicBoolean();
    ServletExchangeHelper.State state =
        ServletExchangeHelper.enter(failingAsyncRequest(asyncContextRequested), response());

    state.exit(new IllegalStateException("service failed"));

    assertFalse(asyncContextRequested.get());
  }

  @Test
  void matchesTheOfficialJakartaServletEntrypointTypes() throws Exception {
    DynamicServletTypeInstrumentation instrumentation =
        new DynamicServletTypeInstrumentation();

    assertTrue(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(TestFilter.class)));
    assertTrue(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(TestServlet.class)));
    assertTrue(instrumentation.classLoaderOptimization().matches(getClass().getClassLoader()));

    Method filter =
        TestFilter.class.getDeclaredMethod(
            "doFilter", ServletRequest.class, ServletResponse.class, FilterChain.class);
    Method service =
        TestServlet.class.getDeclaredMethod(
            "service", ServletRequest.class, ServletResponse.class);
    assertTrue(
        DynamicServletTypeInstrumentation.entrypointMatcher()
            .matches(new MethodDescription.ForLoadedMethod(filter)));
    assertTrue(
        DynamicServletTypeInstrumentation.entrypointMatcher()
            .matches(new MethodDescription.ForLoadedMethod(service)));
  }

  private static final class TestFilter implements Filter {
    @Override
    public void doFilter(
        ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {}
  }

  private static HttpServletRequest failingAsyncRequest(AtomicBoolean asyncContextRequested) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            DynamicServletTypeInstrumentationTest.class.getClassLoader(),
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

  private static HttpServletResponse response() {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            DynamicServletTypeInstrumentationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, arguments) -> {
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

  private static final class TestServlet implements Servlet {
    @Override
    public void init(ServletConfig config) {}

    @Override
    public ServletConfig getServletConfig() {
      return null;
    }

    @Override
    public void service(ServletRequest request, ServletResponse response) {}

    @Override
    public String getServletInfo() {
      return "";
    }

    @Override
    public void destroy() {}
  }
}
