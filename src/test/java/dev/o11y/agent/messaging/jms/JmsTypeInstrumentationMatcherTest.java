package dev.o11y.agent.messaging.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

class JmsTypeInstrumentationMatcherTest {
  private static final String NAMESPACE = "dev.o11y.agent.messaging.jms.fixture";

  @Test
  void producerMatcherCoversClassicAndSupportedConvenienceOverloadsOnly() throws Exception {
    JmsProducerTypeInstrumentation instrumentation =
        new JmsProducerTypeInstrumentation(NAMESPACE);

    assertMatchesType(instrumentation.typeMatcher(), "ClassicProducer");
    assertEquals(
        Set.of(
            "send(Message)",
            "send(Destination,Message)",
            "send(Message,int,int,long)",
            "send(Destination,Message,int,int,long)"),
        matchedMethods(instrumentation.methodMatcher(), "ClassicProducer"));

    assertMatchesType(instrumentation.typeMatcher(), "ModernProducer");
    assertEquals(
        Set.of("send(Destination,Message)", "send(Destination,String)", "send(Destination,byte[])"),
        matchedMethods(instrumentation.methodMatcher(), "ModernProducer"));
  }

  @Test
  void consumerMatcherCoversReceiveVariantsAndRequiresMessageReturnType() throws Exception {
    JmsConsumerTypeInstrumentation instrumentation =
        new JmsConsumerTypeInstrumentation(NAMESPACE);

    assertMatchesType(instrumentation.typeMatcher(), "SyncConsumer");
    assertEquals(
        Set.of("receive()", "receive(long)", "receiveNoWait()"),
        matchedMethods(instrumentation.methodMatcher(), "SyncConsumer"));
  }

  @Test
  void listenerMatcherAcceptsOnlySingleMessageArgument() throws Exception {
    JmsListenerTypeInstrumentation instrumentation =
        new JmsListenerTypeInstrumentation(NAMESPACE);

    assertMatchesType(instrumentation.typeMatcher(), "AsyncListener");
    assertEquals(
        Set.of("onMessage(Message)"),
        matchedMethods(instrumentation.methodMatcher(), "AsyncListener"));
  }

  private static void assertMatchesType(
      ElementMatcher<? super TypeDescription> matcher, String simpleName) throws Exception {
    assertTrue(matcher.matches(new TypeDescription.ForLoadedType(fixtureClass(simpleName))));
  }

  private static Set<String> matchedMethods(
      ElementMatcher<? super MethodDescription> matcher, String simpleName) throws Exception {
    return Arrays.stream(fixtureClass(simpleName).getDeclaredMethods())
        .filter(method -> matcher.matches(new MethodDescription.ForLoadedMethod(method)))
        .map(JmsTypeInstrumentationMatcherTest::signature)
        .collect(Collectors.toSet());
  }

  private static Class<?> fixtureClass(String simpleName) throws ClassNotFoundException {
    return Class.forName(NAMESPACE + "." + simpleName);
  }

  private static String signature(Method method) {
    return method.getName()
        + Arrays.stream(method.getParameterTypes())
            .map(Class::getSimpleName)
            .collect(Collectors.joining(",", "(", ")"));
  }
}
