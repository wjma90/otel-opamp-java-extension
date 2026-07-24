package dev.o11y.agent.servlet.javax;

import java.io.IOException;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

/** Completes capture only after a legacy asynchronous Servlet exchange has finished. */
final class JavaxServletExchangeAsyncListener implements AsyncListener {
  private final JavaxServletExchangeHelper.State state;

  JavaxServletExchangeAsyncListener(JavaxServletExchangeHelper.State state) {
    this.state = state;
  }

  @Override
  public void onComplete(AsyncEvent event) {
    completeSafely(JavaxServletExchangeHelper.State.CompletionOutcome.COMPLETED);
  }

  @Override
  public void onTimeout(AsyncEvent event) {
    completeSafely(JavaxServletExchangeHelper.State.CompletionOutcome.TIMED_OUT);
  }

  @Override
  public void onError(AsyncEvent event) {
    completeSafely(JavaxServletExchangeHelper.State.CompletionOutcome.FAILED);
  }

  @Override
  public void onStartAsync(AsyncEvent event) throws IOException {
    event.getAsyncContext().addListener(this);
  }

  private void completeSafely(JavaxServletExchangeHelper.State.CompletionOutcome outcome) {
    try {
      state.complete(outcome);
    } catch (RuntimeException ignored) {
      // Telemetry must never change the Servlet application's outcome.
    }
  }
}
