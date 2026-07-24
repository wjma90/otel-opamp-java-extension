package dev.o11y.agent.servlet;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Objects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

final class DynamicServletTypeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("jakarta.servlet.ServletRequest", "jakarta.servlet.ServletResponse");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(namedOneOf("jakarta.servlet.Filter", "jakarta.servlet.Servlet"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        entrypointMatcher(),
        DynamicServletTypeInstrumentation.class.getName() + "$ServletAdvice");
  }

  static ElementMatcher.Junction<MethodDescription> entrypointMatcher() {
    ElementMatcher.Junction<MethodDescription> requestAndResponse =
        takesArgument(0, named("jakarta.servlet.ServletRequest"))
            .and(takesArgument(1, named("jakarta.servlet.ServletResponse")));
    return named("service")
        .and(takesArguments(2))
        .and(requestAndResponse)
        .or(named("doFilter").and(takesArguments(3)).and(requestAndResponse));
  }

  @SuppressWarnings("unused")
  public static final class ServletAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ServletExchangeHelper.State enter(
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object request,
        @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object response) {
      ServletExchangeHelper.State state = ServletExchangeHelper.enter(request, response);
      request = Objects.requireNonNull(state.request(request));
      response = Objects.requireNonNull(state.response(response));
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter ServletExchangeHelper.State state,
        @Advice.Thrown Throwable failure) {
      if (state != null) {
        state.exit(failure);
      }
    }
  }
}
