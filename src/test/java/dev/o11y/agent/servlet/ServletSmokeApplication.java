package dev.o11y.agent.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/** Minimal non-Spring Servlet process used by the real Java Agent smoke test. */
public final class ServletSmokeApplication {
  private ServletSmokeApplication() {}

  public static void main(String[] args) throws Exception {
    CountDownLatch stop = new CountDownLatch(1);
    int port;
    try (ServerSocket available = new ServerSocket(0)) {
      port = available.getLocalPort();
    }
    Tomcat tomcat = new Tomcat();
    tomcat.setBaseDir(Files.createTempDirectory("o11y-servlet-smoke-").toString());
    tomcat.setPort(port);
    tomcat.getConnector();
    Context context = tomcat.addContext("", null);
    var exchange = Tomcat.addServlet(context, "exchange", new ExchangeServlet());
    exchange.setAsyncSupported(true);
    context.addServletMappingDecoded("/api/exchanges", "exchange");
    Tomcat.addServlet(context, "stop", new StopServlet(stop));
    context.addServletMappingDecoded("/__stop", "stop");
    tomcat.start();
    System.out.println("O11Y_SMOKE_READY=" + tomcat.getConnector().getLocalPort());
    System.out.flush();
    stop.await();
    // Allow batch signal processors to flush before SDK shutdown.
    Thread.sleep(1200);
    tomcat.stop();
    tomcat.destroy();
  }

  private static final class ExchangeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      request.getReader().transferTo(Writer.nullWriter());
      AsyncContext async = request.startAsync();
      async.start(
          () -> {
            try {
              Thread.sleep(50);
              HttpServletResponse asyncResponse = (HttpServletResponse) async.getResponse();
              asyncResponse.setStatus(HttpServletResponse.SC_CREATED);
              asyncResponse.setContentType("application/json");
              asyncResponse.setHeader("X-Rate-Source", "INTERNAL");
              asyncResponse
                  .getOutputStream()
                  .write(
                      """
                      {"status":"APPROVED","customerType":"STANDARD","rateType":"STANDARD",
                       "receivedAmount":718.76}
                      """.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } catch (IOException ignored) {
              // The smoke process reports an incomplete response to its parent test.
            } finally {
              async.complete();
            }
          });
    }
  }

  private static final class StopServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final transient CountDownLatch stop;

    private StopServlet(CountDownLatch stop) {
      this.stop = stop;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      stop.countDown();
    }
  }
}
