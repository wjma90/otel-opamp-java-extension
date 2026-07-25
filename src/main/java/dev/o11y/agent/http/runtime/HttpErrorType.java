package dev.o11y.agent.http.runtime;

import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Resolves the low-cardinality {@code error.type} defined by the OTel HTTP conventions. */
public final class HttpErrorType {
  private static final int MAX_UNWRAP_DEPTH = 8;

  private HttpErrorType() {}

  public static String resolve(String direction, int responseStatus, Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause != null) {
      if (isTimeout(cause)) {
        return "timeout";
      }
      String className = cause.getClass().getName();
      return className == null || className.isBlank() ? "unknown" : className;
    }
    if (responseStatus <= 0) {
      return null;
    }
    boolean failed =
        "INCOMING".equalsIgnoreCase(direction)
            ? responseStatus >= 500
            : "OUTGOING".equalsIgnoreCase(direction) && responseStatus >= 400;
    return failed ? String.valueOf(responseStatus) : null;
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_UNWRAP_DEPTH; depth++) {
      if (!(current instanceof CompletionException
          || current instanceof ExecutionException
          || current instanceof InvocationTargetException)) {
        return current;
      }
      Throwable cause = current.getCause();
      if (cause == null || cause == current) {
        return current;
      }
      current = cause;
    }
    return current;
  }

  private static boolean isTimeout(Throwable failure) {
    return failure instanceof TimeoutException
        || failure instanceof HttpTimeoutException
        || failure instanceof java.net.SocketTimeoutException;
  }
}
