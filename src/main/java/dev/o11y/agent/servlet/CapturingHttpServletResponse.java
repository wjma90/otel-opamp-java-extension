package dev.o11y.agent.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CapturingHttpServletResponse extends HttpServletResponseWrapper {
  private final BoundedBodyCapture capture;
  private ServletOutputStream outputStream;
  private PrintWriter writer;

  CapturingHttpServletResponse(HttpServletResponse response, int limit) {
    super(response);
    capture = new BoundedBodyCapture(limit);
  }

  byte[] capturedBody() {
    if (writer != null) {
      writer.flush();
    }
    return capture.bytes();
  }

  void clearCapturedBody() {
    capture.clear();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (writer != null) {
      throw new IllegalStateException("getWriter() has already been called");
    }
    if (outputStream == null) {
      outputStream = new CapturingServletOutputStream(super.getOutputStream(), capture);
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer != null) {
      return writer;
    }
    if (outputStream != null) {
      throw new IllegalStateException("getOutputStream() has already been called");
    }
    outputStream = new CapturingServletOutputStream(super.getOutputStream(), capture);
    writer = new PrintWriter(new OutputStreamWriter(outputStream, characterEncoding()), false);
    return writer;
  }

  @Override
  public void flushBuffer() throws IOException {
    if (writer != null) {
      writer.flush();
    } else if (outputStream != null) {
      outputStream.flush();
    }
    super.flushBuffer();
  }

  private Charset characterEncoding() {
    String configured = getCharacterEncoding();
    if (configured == null || configured.isBlank()) {
      return StandardCharsets.ISO_8859_1;
    }
    try {
      return Charset.forName(configured);
    } catch (RuntimeException ignored) {
      return StandardCharsets.ISO_8859_1;
    }
  }
}
