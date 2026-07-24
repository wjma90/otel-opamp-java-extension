package dev.o11y.agent.http.client.okhttp;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/** Tees one-shot request serialization into the bounded policy capture. */
public final class OkHttpRequestBodyTypeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed(
        OkHttpClientBridge.requestBodyClassName(), OkHttpClientBridge.bufferedSinkClassName());
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasSuperType(named(OkHttpClientBridge.requestBodyClassName()))
        .and(not(nameStartsWith(OkHttpClientBridge.internalShadedPrefix())));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> writeTo =
        named("writeTo")
            .and(not(isAbstract()))
            .and(takesArguments(1))
            .and(takesArgument(0, named(OkHttpClientBridge.bufferedSinkClassName())));
    transformer.applyAdviceToMethod(
        writeTo, OkHttpRequestBodyTypeInstrumentation.class.getName() + "$WriteAdvice");
  }

  /** Names a parent instrumentation module can register as application-loader helpers. */
  public static List<String> helperClassNames() {
    return OkHttpClientBridge.helperClassNames();
  }

  @SuppressWarnings("unused")
  public static final class WriteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static OkHttpClientBridge.RequestWriteState enter(
        @Advice.This Object requestBody,
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object sink) {
      OkHttpClientBridge.RequestWriteState state =
          OkHttpClientBridge.beginRequestWrite(requestBody, sink);
      sink = state.sink();
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter OkHttpClientBridge.RequestWriteState state,
        @Advice.Thrown Throwable failure) {
      OkHttpClientBridge.finishRequestWrite(state, failure);
    }
  }
}
