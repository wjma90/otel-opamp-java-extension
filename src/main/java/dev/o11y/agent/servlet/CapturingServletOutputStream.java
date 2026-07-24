package dev.o11y.agent.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;

final class CapturingServletOutputStream extends ServletOutputStream {
  private final ServletOutputStream delegate;
  private final BoundedBodyCapture capture;

  CapturingServletOutputStream(ServletOutputStream delegate, BoundedBodyCapture capture) {
    this.delegate = delegate;
    this.capture = capture;
  }

  @Override
  public void write(int value) throws IOException {
    capture.write(value);
    delegate.write(value);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    capture.write(bytes, offset, length);
    delegate.write(bytes, offset, length);
  }

  @Override
  public boolean isReady() {
    return delegate.isReady();
  }

  @Override
  public void setWriteListener(WriteListener listener) {
    delegate.setWriteListener(listener);
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
