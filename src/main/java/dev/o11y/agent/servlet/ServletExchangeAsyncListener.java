package dev.o11y.agent.servlet;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.IOException;

/** Completes capture only after an asynchronous Servlet exchange has actually finished. */
final class ServletExchangeAsyncListener implements AsyncListener {
  private final ServletExchangeHelper.State state;

  ServletExchangeAsyncListener(ServletExchangeHelper.State state) {
    this.state = state;
  }

  @Override
  public void onComplete(AsyncEvent event) {
    completeSafely(ServletExchangeHelper.State.CompletionOutcome.COMPLETED, null);
  }

  @Override
  public void onTimeout(AsyncEvent event) {
    completeSafely(ServletExchangeHelper.State.CompletionOutcome.TIMED_OUT, null);
  }

  @Override
  public void onError(AsyncEvent event) {
    completeSafely(ServletExchangeHelper.State.CompletionOutcome.FAILED, event.getThrowable());
  }

  @Override
  public void onStartAsync(AsyncEvent event) throws IOException {
    event.getAsyncContext().addListener(this);
  }

  private void completeSafely(
      ServletExchangeHelper.State.CompletionOutcome outcome, Throwable failure) {
    try {
      state.complete(outcome, failure);
    } catch (RuntimeException ignored) {
      // Telemetry must never change the Servlet application's outcome.
    }
  }
}
