package dev.o11y.agent.http.server.quarkus;

import dev.o11y.agent.servlet.BoundedBodyCapture;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class QuarkusCapturingInputStream extends FilterInputStream {
  private final BoundedBodyCapture capture;

  QuarkusCapturingInputStream(InputStream delegate, BoundedBodyCapture capture) {
    super(delegate);
    this.capture = capture;
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value >= 0) {
      capture.write(value);
    }
    return value;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    int read = super.read(bytes, offset, length);
    if (read > 0) {
      capture.write(bytes, offset, read);
    }
    return read;
  }
}
