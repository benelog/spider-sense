package net.benelog.spidersense.launcher;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * <p>It is compiled by the test source set but never run by JUnit: SingleJarIT spawns it in a JVM
 * of its own with {@code -javaagent}.
 */
public final class SampleApp {

    private SampleApp() {
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
