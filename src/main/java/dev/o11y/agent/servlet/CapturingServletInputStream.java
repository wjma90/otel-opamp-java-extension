package dev.o11y.agent.servlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;

final class CapturingServletInputStream extends ServletInputStream {
  private final ServletInputStream delegate;
  private final BoundedBodyCapture capture;

  CapturingServletInputStream(ServletInputStream delegate, BoundedBodyCapture capture) {
    this.delegate = delegate;
    this.capture = capture;
  }

  @Override
  public int read() throws IOException {
    int result = delegate.read();
    if (result >= 0) {
      capture.write(result);
    }
    return result;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    int result = delegate.read(bytes, offset, length);
    if (result > 0) {
      capture.write(bytes, offset, result);
    }
    return result;
  }

  @Override
  public boolean isFinished() {
    return delegate.isFinished();
  }

  @Override
  public boolean isReady() {
    return delegate.isReady();
  }

  @Override
  public void setReadListener(ReadListener listener) {
    delegate.setReadListener(listener);
  }
}
