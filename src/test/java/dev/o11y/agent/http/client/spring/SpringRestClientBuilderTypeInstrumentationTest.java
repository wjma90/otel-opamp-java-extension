package dev.o11y.agent.http.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.DefaultRestClientBuilder;

class SpringRestClientBuilderTypeInstrumentationTest {
  @Test
  void matchesSpringDefaultRestClientBuilderAndInstallsLastBeforeEveryBuild() {
    SpringRestClientBuilderTypeInstrumentation instrumentation =
        new SpringRestClientBuilderTypeInstrumentation();

    assertTrue(
        instrumentation
            .typeMatcher()
            .matches(new TypeDescription.ForLoadedType(DefaultRestClientBuilder.class)));
    assertTrue(instrumentation.classLoaderOptimization().matches(getClass().getClassLoader()));

    DefaultRestClientBuilder builder = new DefaultRestClientBuilder();
    ClientHttpRequestInterceptor first =
        (request, body, execution) -> execution.execute(request, body);
    ClientHttpRequestInterceptor later =
        (request, body, execution) -> execution.execute(request, body);
    builder.requestInterceptor(first);
    SpringRestClientBuilderTypeInstrumentation.BuildAdvice.enter(builder);
    builder.requestInterceptor(later);
    SpringRestClientBuilderTypeInstrumentation.BuildAdvice.enter(builder);

    List<ClientHttpRequestInterceptor> interceptors = interceptors(builder);
    assertEquals(3, interceptors.size());
    assertSame(first, interceptors.get(0));
    assertSame(later, interceptors.get(1));
    assertTrue(Proxy.isProxyClass(interceptors.getLast().getClass()));
    assertEquals(
        1,
        interceptors.stream().filter(value -> Proxy.isProxyClass(value.getClass())).count());
  }

  @Test
  void constructorFallbackMovesAnExistingPolicyInterceptorToTheEnd() {
    DefaultRestClientBuilder builder = new DefaultRestClientBuilder();
    SpringRestClientBridge.install(builder);
    ClientHttpRequestInterceptor policy = interceptors(builder).getFirst();
    ClientHttpRequestInterceptor first =
        (request, body, execution) -> execution.execute(request, body);
    ClientHttpRequestInterceptor last =
        (request, body, execution) -> execution.execute(request, body);

    @SuppressWarnings("unchecked")
    List<ClientHttpRequestInterceptor> reordered =
        (List<ClientHttpRequestInterceptor>)
            SpringRestClientBridge.appendInterceptor(
                List.of(first, policy, last, policy), getClass().getClassLoader());

    assertEquals(List.of(first, last, policy), reordered);
  }

  @Test
  void moduleDoesNotRegisterTheEarlyFactoryHook() {
    assertFalse(
        new SpringRestClientInstrumentationModule().typeInstrumentations().stream()
            .anyMatch(SpringRestClientFactoryTypeInstrumentation.class::isInstance));
  }

  private static List<ClientHttpRequestInterceptor> interceptors(
      DefaultRestClientBuilder builder) {
    AtomicReference<List<ClientHttpRequestInterceptor>> result = new AtomicReference<>();
    Consumer<List<ClientHttpRequestInterceptor>> snapshot =
        current -> result.set(List.copyOf(current));
    try {
      Method accessor = builder.getClass().getMethod("requestInterceptors", Consumer.class);
      if (!accessor.canAccess(builder)) {
        accessor.setAccessible(true);
      }
      accessor.invoke(builder, snapshot);
      return result.get();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
