package dev.o11y.agent.servlet.javax;

import dev.o11y.agent.servlet.BoundedBodyCapture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.servlet.AsyncContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

final class CapturingHttpServletRequest extends HttpServletRequestWrapper {
  private final HttpServletRequest originalRequest;
  private final BoundedBodyCapture capture;
  private final CapturingHttpServletResponse response;
  private ServletInputStream inputStream;
  private BufferedReader reader;

  CapturingHttpServletRequest(
      HttpServletRequest request, CapturingHttpServletResponse response, int limit) {
    super(request);
    this.originalRequest = request;
    this.response = response;
    capture = new BoundedBodyCapture(limit);
  }

  byte[] capturedBody() {
    return capture.bytes();
  }

  void clearCapturedBody() {
    capture.clear();
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (reader != null) {
      throw new IllegalStateException("getReader() has already been called");
    }
    if (inputStream == null) {
      inputStream = new CapturingServletInputStream(super.getInputStream(), capture);
    }
    return inputStream;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    if (reader != null) {
      return reader;
    }
    if (inputStream != null) {
      throw new IllegalStateException("getInputStream() has already been called");
    }
    inputStream = new CapturingServletInputStream(super.getInputStream(), capture);
    reader = new BufferedReader(new InputStreamReader(inputStream, characterEncoding()));
    return reader;
  }

  @Override
  public AsyncContext startAsync() throws IllegalStateException {
    return response == null ? super.startAsync() : super.startAsync(this, response);
  }

  @Override
  public AsyncContext startAsync(ServletRequest request, ServletResponse response)
      throws IllegalStateException {
    ServletRequest asyncRequest = request == originalRequest ? this : request;
    ServletResponse asyncResponse =
        this.response != null && response == this.response.getResponse() ? this.response : response;
    return super.startAsync(asyncRequest, asyncResponse);
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
