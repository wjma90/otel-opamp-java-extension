package dev.o11y.agent.http.server.quarkus;

import dev.o11y.agent.servlet.BoundedBodyCapture;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class QuarkusCapturingOutputStream extends FilterOutputStream {
  private final Object exchange;
  private final BoundedBodyCapture capture;

  QuarkusCapturingOutputStream(
      Object exchange, OutputStream delegate, BoundedBodyCapture capture) {
    super(delegate);
    this.exchange = exchange;
    this.capture = capture;
  }

  @Override
  public void write(int value) throws IOException {
    capture.write(value);
    out.write(value);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    capture.write(bytes, offset, length);
    out.write(bytes, offset, length);
  }

  @Override
  public void close() throws IOException {
    Throwable failure = null;
    try {
      super.close();
    } catch (IOException | RuntimeException | Error error) {
      failure = error;
      throw error;
    } finally {
      QuarkusRestExchangeHelper.complete(exchange, failure);
    }
  }
}
