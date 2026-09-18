package net.benelog.spidersense.launcher;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The application SingleJarIT monitors: a tiny HTTP server, three calls to it, then long enough for
 * the OpenTelemetry agent to export them, then a plain return from main.
 *
 * <p>It deliberately does not call Spider Sense's own port: the collector drops spans whose
 * {@code server.port} is its own and whose service is the one it is embedded in (design.md, "belt
 * and braces"), so such a call would be invisible and prove nothing.
 *
 * <p>With {@code -Dsample.db.sleep.ms=<n>} it also runs one deliberately slow H2 statement, so the
 * packaged extension has a database span to hang a {@code code.stacktrace} on. It is off by default
 * because the other cases assert that the sample touches no database at all.
 *
 * <p>It is compiled by the test source set but never run by JUnit: SingleJarIT spawns it in a JVM
 * of its own with {@code -javaagent}.
 */
public final class SampleApp {

    private SampleApp() {
    }

    /**
     * One query that is slow by decree, from a method with a name worth finding in a stack trace.
     *
     * <p>H2 has no {@code SLEEP} of its own, so it gets one: the alias is the trick
     * {@code examples/silk-bookstore} uses, with the parameter type spelled out because
     * {@code sleep(long)} and {@code sleep(Duration)} are both there.
     */
    static void slowQuery(long millis) throws Exception {
        String url = "jdbc:h2:mem:sample-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create alias if not exists sleep for 'java.lang.Thread.sleep(long)'");
            }
            try (PreparedStatement statement = connection.prepareStatement("select sleep(?)")) {
                statement.setLong(1, millis);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        // Drain it; the value is nothing, the time it took is the point.
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread t = new Thread(runnable, "sample-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/hello", exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hello");
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 3; i++) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(target).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                System.out.println("sample: GET " + target + " -> " + response.statusCode());
                Thread.sleep(100);
            }
            long databaseSleep = Long.getLong("sample.db.sleep.ms", 0L);
            if (databaseSleep > 0) {
                slowQuery(databaseSleep);
                System.out.println("sample: select sleep(" + databaseSleep + ")");
            }
        } finally {
            // The dispatcher thread of HttpServer is not a daemon; without this the JVM would stay
            // up and the test could not tell our threads from its own.
            server.stop(0);
            pool.shutdownNow();
        }
        // otel.bsp.schedule.delay is 1000 ms by our defaults; give the batch two turns. The CLI test
        // keeps the application up longer than that, because it runs a handful of commands against
        // the UI inside it and the UI dies with the application.
        Thread.sleep(Long.getLong("sample.linger.ms", 4000L));
        System.out.println("sample: done");
    }
}
