package dev.o11y.agent.http.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class HttpErrorTypeTest {
  @Test
  void omitsSuccessfulAndNonErrorServerStatuses() {
    assertNull(HttpErrorType.resolve("INCOMING", 200, null));
    assertNull(HttpErrorType.resolve("INCOMING", 404, null));
    assertNull(HttpErrorType.resolve("OUTGOING", 200, null));
  }

  @Test
  void usesStatusCodesAccordingToHttpClientAndServerSemantics() {
    assertEquals("500", HttpErrorType.resolve("INCOMING", 500, null));
    assertEquals("404", HttpErrorType.resolve("OUTGOING", 404, null));
    assertEquals("503", HttpErrorType.resolve("OUTGOING", 503, null));
  }

  @Test
  void reportsTimeoutsAndUnwrappedTransportExceptions() {
    assertEquals(
        "timeout",
        HttpErrorType.resolve(
            "OUTGOING", 0, new CompletionException(new SocketTimeoutException())));
    assertEquals(
        "java.net.UnknownHostException",
        HttpErrorType.resolve("OUTGOING", 0, new UnknownHostException("private-host")));
  }
}
