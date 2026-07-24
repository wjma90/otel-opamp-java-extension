package dev.o11y.agent.http.server.quarkus;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

final class QuarkusRestTypeInstrumentation implements TypeInstrumentation {
  static final String QUARKUS_REQUEST_CONTEXT =
      "io.quarkus.resteasy.reactive.server.runtime.QuarkusResteasyReactiveRequestContext";
  static final String VERTX_REQUEST_CONTEXT =
      "org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext";

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(QUARKUS_REQUEST_CONTEXT, VERTX_REQUEST_CONTEXT);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(VERTX_REQUEST_CONTEXT);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor(), QuarkusRestTypeInstrumentation.class.getName() + "$ConstructorAdvice");
    transformer.applyAdviceToMethod(
        named("createInputStream").and(takesArguments(0).or(takesArguments(1))),
        QuarkusRestTypeInstrumentation.class.getName() + "$InputStreamAdvice");
    transformer.applyAdviceToMethod(
        named("setReadListener").and(takesArguments(1)),
        QuarkusRestTypeInstrumentation.class.getName() + "$ReadListenerAdvice");
    transformer.applyAdviceToMethod(
        named("createResponseOutputStream").and(takesArguments(0)),
        QuarkusRestTypeInstrumentation.class.getName() + "$OutputStreamAdvice");
    transformer.applyAdviceToMethod(
        named("reset").and(takesArguments(0)),
        QuarkusRestTypeInstrumentation.class.getName() + "$ResetAdvice");
    transformer.applyAdviceToMethod(
        namedOneOf("write", "end"),
        QuarkusRestTypeInstrumentation.class.getName() + "$ResponseAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object exchange) {
      QuarkusRestExchangeHelper.resetResponse(exchange);
    }
  }

  @SuppressWarnings("unused")
  public static final class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object exchange) {
      QuarkusRestExchangeHelper.start(exchange);
    }
  }

  @SuppressWarnings("unused")
  public static final class InputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object exchange,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object input) {
      QuarkusRestExchangeHelper.bindContext(exchange);
      input = QuarkusRestExchangeHelper.wrapInput(exchange, input);
    }
  }

  @SuppressWarnings("unused")
  public static final class ReadListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.This Object exchange,
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object callback) {
      QuarkusRestExchangeHelper.bindContext(exchange);
      callback = QuarkusRestExchangeHelper.wrapReadCallback(exchange, callback);
    }
  }

  @SuppressWarnings("unused")
  public static final class OutputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object exchange,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object output) {
      QuarkusRestExchangeHelper.bindContext(exchange);
      output = QuarkusRestExchangeHelper.wrapOutput(exchange, output);
    }
  }

  @SuppressWarnings("unused")
  public static final class ResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.This Object exchange,
        @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
            Object[] arguments) {
      QuarkusRestExchangeHelper.bindContext(exchange);
      QuarkusRestExchangeHelper.captureResponseArguments(exchange, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.This Object exchange,
        @Advice.Origin("#m") String method,
        @Advice.Thrown Throwable failure) {
      if ("end".equals(method)) {
        QuarkusRestExchangeHelper.complete(exchange, failure);
      }
    }
  }
}
