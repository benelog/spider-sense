package net.benelog.spidersense.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;
import net.benelog.spidersilk.json.Json;

/** The entry point: it binds a port, answers, and lets go of everything on stop. */
class SpiderSenseServerTest {

    @Test
    void startsOnAFreePortAnswersStatusAndStops() throws Exception {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            assertThat(server.port()).isPositive();

            HttpResponse<String> status = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/status"))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(status.statusCode()).isEqualTo(200);
            Json.JsonObject body = Json.parse(status.body()).asObject();
            assertThat(body.getString("name")).isEqualTo("Spider Sense");
            assertThat(body.getString("mode")).isEqualTo("standalone");
            assertThat(body.getString("endpoint")).isEqualTo("http://127.0.0.1:" + server.port());
        } finally {
            server.stop();
        }
    }

    /**
     * In agent mode nothing we start may outlive the monitored application's own
     * {@code main} — a command-line program with Spider Sense attached must still
     * exit. That means the writer, the sweeper, Jetty's pool <em>and</em> Jetty's
     * scheduler, which is not a daemon by default.
     */
    @Test
    void agentModeStartsNoThreadThatWouldKeepTheHostJvmAlive() throws Exception {
        java.util.Set<Thread> before = Thread.getAllStackTraces().keySet();
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config("--mode=agent"));
        try {
            assertThat(server.config().agentMode()).isTrue();
            Thread.sleep(200);
            java.util.List<String> nonDaemon = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !before.contains(thread))
                    .filter(Thread::isAlive)
                    .filter(thread -> !thread.isDaemon())
                    .map(Thread::getName)
                    .toList();
            assertThat(nonDaemon).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void anUnopenableDatabaseFallsBackToMemoryRatherThanFailingToStart() {
        // A path that cannot be a directory: the fallback is what keeps premain safe.
        Config config = Config.parse(new String[]{"--port=0", "--db=/proc/version/sense"});
        SpiderSenseServer server = SpiderSenseServer.start(config);
        try {
            var storage = server.store().database().storage();
            assertThat(storage.fallback()).isTrue();
            assertThat(storage.url()).startsWith("jdbc:h2:mem:");
            assertThat(storage.fallbackReason()).isNotBlank();
        } finally {
            server.stop();
        }
    }

    /** A web page's cross-origin request, and one under a rebound host name, are turned away. */
    @Test
    void aForeignOriginOrHostIsForbidden() throws Exception {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            int port = server.port();
            HttpResponse<String> foreign = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/marks"))
                            .header("Content-Type", "text/plain")
                            .header("Origin", "http://evil.example")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThat(server.store().sql().count("SELECT COUNT(*) FROM mark WHERE name = 'x'",
                    java.util.List.of())).isZero();

            HttpResponse<String> own = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                            .header("Origin", "http://127.0.0.1:" + port)
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(own.statusCode()).isEqualTo(200);

            assertThat(statusLine(port, "evil.example:" + port)).contains(" 403 ");
            assertThat(statusLine(port, "localhost:" + port)).contains(" 200 ");
        } finally {
            server.stop();
        }
    }

    @Test
    void theHostCheckAcceptsLoopbackNamesAndAnyNameOnAWildcardBind() {
        assertThat(LocalRequests.name("[::1]:4000")).isEqualTo("::1");
        assertThat(LocalRequests.name("LocalHost:4000")).isEqualTo("localhost");
        assertThat(LocalRequests.name("::1")).isEqualTo("::1");
        assertThat(LocalRequests.acceptedHost("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(LocalRequests.acceptedHost("localhost", "127.0.0.1")).isTrue();
        assertThat(LocalRequests.acceptedHost("::1", "127.0.0.1")).isTrue();
        assertThat(LocalRequests.acceptedHost("evil.example", "127.0.0.1")).isFalse();
        assertThat(LocalRequests.acceptedHost("192.168.1.5", "192.168.1.5")).isTrue();
        assertThat(LocalRequests.acceptedHost("my-laptop", "0.0.0.0")).isTrue();
        assertThat(LocalRequests.acceptedHost("my-laptop", "::")).isTrue();
    }

    /** The status line of a GET under a {@code Host} the JDK's client will not send. */
    private static String statusLine(int port, String host) throws IOException {
        try (Socket socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    /** A service whose name holds a slash is reached through %2F, and a miss is the JSON 404. */
    @Test
    void anEncodedSlashInAPathSegmentIsPartOfTheName() throws Exception {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/services/a%2Fb"))
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            assertThat(response.body()).contains("a/b");

            for (String escape : new String[] {"/assets/..%2F..%2Fsimplelogger.properties",
                    "/assets/js/..%2F..%2F..%2Fsimplelogger.properties", "/..%2Fsimplelogger.properties"}) {
                HttpResponse<String> outside = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + escape))
                                .timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(outside.body()).as(escape).doesNotContain("defaultLogLevel");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Whatever Jetty refuses before routing answers in the API's error shape, not Jetty's HTML
     * page: a name holding '%' is reached as %25, and %5C or %2E%2E is a JSON 400.
     */
    @Test
    void aPathJettyDeemsAmbiguousStillAnswersJson() throws Exception {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            HttpResponse<String> percent = get(server, "/api/services/a%25b");
            assertThat(percent.statusCode()).isEqualTo(404);
            assertThat(percent.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            assertThat(percent.body()).contains("a%b");

            for (String path : new String[] {"/api/services/a%5Cb", "/api/services/%2E%2E",
                    "/api/services/a%5Cb?x=1"}) {
                HttpResponse<String> refused = get(server, path);
                assertThat(refused.statusCode()).as(path).isEqualTo(400);
                assertThat(refused.headers().firstValue("Content-Type")).as(path).hasValueSatisfying(
                        type -> assertThat(type).startsWith("application/json"));
                assertThat(refused.body()).as(path).startsWith("{\"error\":").doesNotContain("<html");
            }

            for (String escape : new String[] {"/assets/..%252F..%252Fsimplelogger.properties",
                    "/assets/%2E%2E/%2E%2E/simplelogger.properties", "/assets/..%5C..%5Csimplelogger.properties"}) {
                assertThat(get(server, escape).body()).as(escape).doesNotContain("defaultLogLevel");
            }
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(SpiderSenseServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
