package dev.o11y.agent.servlet;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Keeps at most {@code configuredLimit + 1} bytes so truncation is detectable. */
public final class BoundedBodyCapture extends ByteArrayOutputStream {
  private final int storageLimit;

  public BoundedBodyCapture(int configuredLimit) {
    super(Math.min(configuredLimit + 1, 8192));
    storageLimit = configuredLimit + 1;
  }

  @Override
  public synchronized void write(int value) {
    if (size() < storageLimit) {
      super.write(value);
    }
  }

  @Override
  public synchronized void write(byte[] source, int offset, int length) {
    int remaining = storageLimit - size();
    if (remaining > 0) {
      super.write(source, offset, Math.min(remaining, length));
    }
  }

  public synchronized byte[] bytes() {
    return toByteArray();
  }

  public synchronized void clear() {
    Arrays.fill(buf, (byte) 0);
    reset();
  }

  synchronized boolean storageIsZeroedForTest() {
    for (byte value : buf) {
      if (value != 0) {
        return false;
      }
    }
    return true;
  }
}
