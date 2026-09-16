package net.benelog.spidersense.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
}
