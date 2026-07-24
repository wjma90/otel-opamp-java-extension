package dev.o11y.fixture;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/** Small Servlet application for manual testing; it is never packaged with the extension. */
public final class LocalFixtureApplication {
  private static final int PORT = 18082;

  private LocalFixtureApplication() {}

  public static void main(String[] args) throws Exception {
    Tomcat tomcat = new Tomcat();
    Path baseDirectory = Files.createTempDirectory("o11y-local-fixture-");
    tomcat.setBaseDir(baseDirectory.toString());
    tomcat.setPort(PORT);
    tomcat.getConnector();

    Context context = tomcat.addContext("", null);
    Tomcat.addServlet(context, "exchange", new ExchangeServlet());
    context.addServletMappingDecoded("/api/exchanges", "exchange");
    Tomcat.addServlet(context, "health", new HealthServlet());
    context.addServletMappingDecoded("/healthz", "health");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    tomcat.stop();
                    tomcat.destroy();
                  } catch (Exception ignored) {
                    // The process is already stopping; no recovery action remains.
                  } finally {
                    deleteDirectory(baseDirectory);
                  }
                },
                "local-fixture-shutdown"));

    tomcat.start();
    if (!tomcat.getConnector().getState().isAvailable()) {
      throw new IllegalStateException("HTTP connector did not start on port " + PORT);
    }
    System.out.println("O11Y_LOCAL_FIXTURE_READY=http://127.0.0.1:" + PORT);
    tomcat.getServer().await();
  }

  private static void deleteDirectory(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException failure) {
                  System.err.println(
                      "Could not delete local fixture path " + path + ": " + failure.getMessage());
                }
              });
    } catch (IOException failure) {
      System.err.println(
          "Could not inspect local fixture directory " + root + ": " + failure.getMessage());
    }
  }

  private static final class ExchangeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      request.getReader().transferTo(java.io.Writer.nullWriter());

      response.setStatus(HttpServletResponse.SC_CREATED);
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader("X-Operation-Status", "APPROVED");
      response.setHeader("X-Rate-Type", "PREFERRED");
      response.setHeader("X-Result-Type", "PREFERRED");
      response
          .getWriter()
          .write(
              """
              {"status":"APPROVED","targetAmount":43.99,"targetCurrency":"USD",
               "customer":{"segment":"PREMIUM"}}
              """);
    }
  }

  private static final class HealthServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
  }
}
