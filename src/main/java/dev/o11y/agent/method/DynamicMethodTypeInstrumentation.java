package dev.o11y.agent.method;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

final class DynamicMethodTypeInstrumentation implements TypeInstrumentation {
  private final List<String> packagePrefixes;

  DynamicMethodTypeInstrumentation(List<String> packagePrefixes) {
    this.packagePrefixes = packagePrefixes;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    ElementMatcher.Junction<TypeDescription> matcher = none();
    for (String prefix : packagePrefixes) {
      matcher = matcher.or(nameStartsWith(prefix + "."));
    }
    return matcher;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> methods =
        isMethod().and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic()));
    transformer.applyAdviceToMethod(
        methods.and(not(returns(void.class))),
        DynamicMethodTypeInstrumentation.class.getName() + "$ReturnAdvice");
    transformer.applyAdviceToMethod(
        methods.and(returns(void.class)),
        DynamicMethodTypeInstrumentation.class.getName() + "$VoidAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ReturnAdvice {
    @net.bytebuddy.asm.Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter() {
      return System.nanoTime();
    }

    @net.bytebuddy.asm.Advice.OnMethodExit(
        onThrowable = Throwable.class,
        suppress = Throwable.class)
    public static void exit(
        @net.bytebuddy.asm.Advice.Origin("#t") String className,
        @net.bytebuddy.asm.Advice.Origin("#m") String methodName,
        @net.bytebuddy.asm.Advice.AllArguments Object[] arguments,
        @net.bytebuddy.asm.Advice.Return(
                typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
            Object returned,
        @net.bytebuddy.asm.Advice.Thrown Throwable error,
        @net.bytebuddy.asm.Advice.Enter long startNanos) {
      MethodCaptureHelper.onExit(
          className, methodName, arguments, returned, error, startNanos, System.nanoTime());
    }
  }

  @SuppressWarnings("unused")
  public static final class VoidAdvice {
    @net.bytebuddy.asm.Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter() {
      return System.nanoTime();
    }

    @net.bytebuddy.asm.Advice.OnMethodExit(
        onThrowable = Throwable.class,
        suppress = Throwable.class)
    public static void exit(
        @net.bytebuddy.asm.Advice.Origin("#t") String className,
        @net.bytebuddy.asm.Advice.Origin("#m") String methodName,
        @net.bytebuddy.asm.Advice.AllArguments Object[] arguments,
        @net.bytebuddy.asm.Advice.Thrown Throwable error,
        @net.bytebuddy.asm.Advice.Enter long startNanos) {
      MethodCaptureHelper.onExit(
          className, methodName, arguments, null, error, startNanos, System.nanoTime());
    }
  }
}
