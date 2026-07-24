package dev.o11y.agent.http;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Shares a bounded server-response callback between agent-extension and injected helper class
 * loaders.
 *
 * <p>{@link ContextKey} instances use identity equality. The Java agent loads customizers and
 * application helpers from different class loaders, so both sides obtain the same key through the
 * JVM-owned system properties table. The value itself only contains JDK interfaces and is scoped
 * to one OpenTelemetry request context.
 */
public final class HttpServerCompletionBridge {
  private static final String CONTEXT_KEY_PROPERTY =
      "dev.o11y.internal.http-server-completion-context-key";

  private HttpServerCompletionBridge() {}

  public static Context install(Context context) {
    ContextKey<Object> key = sharedKey();
    return context.with(
        key, new AtomicReference<BiFunction<Context, Throwable, Map<String, Object>>>());
  }

  public static boolean arm(
      Context context, BiFunction<Context, Throwable, Map<String, Object>> completion) {
    ContextKey<Object> key = sharedKey();
    AtomicReference<BiFunction<Context, Throwable, Map<String, Object>>> holder =
        holder(context, key);
    return holder != null && holder.compareAndSet(null, completion);
  }

  public static Map<String, Object> complete(Context context, Throwable failure) {
    ContextKey<Object> key = sharedKey();
    AtomicReference<BiFunction<Context, Throwable, Map<String, Object>>> holder =
        holder(context, key);
    if (holder == null) {
      return Map.of();
    }
    BiFunction<Context, Throwable, Map<String, Object>> completion = holder.getAndSet(null);
    if (completion == null) {
      return Map.of();
    }
    Map<String, Object> attributes = completion.apply(context, failure);
    return attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<BiFunction<Context, Throwable, Map<String, Object>>> holder(
      Context context, ContextKey<Object> key) {
    Object value = context.get(key);
    return value instanceof AtomicReference<?>
        ? (AtomicReference<BiFunction<Context, Throwable, Map<String, Object>>>) value
        : null;
  }

  @SuppressWarnings("unchecked")
  private static ContextKey<Object> sharedKey() {
    Properties properties = System.getProperties();
    Object current = properties.get(CONTEXT_KEY_PROPERTY);
    if (current instanceof ContextKey<?>) {
      return (ContextKey<Object>) current;
    }
    synchronized (properties) {
      current = properties.get(CONTEXT_KEY_PROPERTY);
      if (current instanceof ContextKey<?>) {
        return (ContextKey<Object>) current;
      }
      ContextKey<Object> created = ContextKey.named(CONTEXT_KEY_PROPERTY);
      properties.put(CONTEXT_KEY_PROPERTY, created);
      return created;
    }
  }
}
