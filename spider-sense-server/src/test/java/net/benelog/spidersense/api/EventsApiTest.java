package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.server.SpiderSenseServer;
import net.benelog.spidersense.store.ServiceInfo;

/** The pacing of {@code /api/events} and the arithmetic of its stats frame, and the frames themselves. */
class EventsApiTest {

    private static final long T0 = 1_700_000_000_000L;

    /**
     * The UI answers a {@code service} frame by asking {@code /api/services} for the list:
     * the frame names the service once its row is stored, so the list has it.
     */
    @Test
    void aNewServiceIsAFrameOnceItsRowIsStored() throws Exception {
        SpiderSenseServer server = SpiderSenseServer.start(TestStore.config());
        try {
            HttpResponse<Stream<String>> events = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/events")).build(),
                    HttpResponse.BodyHandlers.ofLines());
            CompletableFuture<Void> subscribed = new CompletableFuture<>();
            CompletableFuture<String> service = CompletableFuture.supplyAsync(() -> {
                Iterator<String> lines = events.body().iterator();
                while (lines.hasNext()) {
                    String line = lines.next();
                    if (line.equals("event: stats")) {
                        // The opening frame, sent once the stream has subscribed.
                        subscribed.complete(null);
                    } else if (line.equals("event: service")) {
                        return lines.next();
                    }
                }
                return "";
            });
            subscribed.get(10, TimeUnit.SECONDS);

            new OtlpDecoder(server.store(), server::port).ingest(Otlp.traces(Otlp.service("billing"),
                    Otlp.span("%032x".formatted(1), "%016x".formatted(1), "GET /invoices",
                            Span.SpanKind.SPAN_KIND_SERVER, System.currentTimeMillis(), 5)));
            String data = service.get(10, TimeUnit.SECONDS);

            assertThat(server.store().services().all()).extracting(ServiceInfo::name).containsExactly("billing");
            JsonObject frame = Json.parse(data.substring("data:".length()).trim()).asObject();
            assertThat(frame.getString("name")).isEqualTo("billing");
            assertThat(frame.getLong("firstSeen")).isEqualTo(server.store().services().all().get(0).firstSeen());
            events.body().close();
        } finally {
            server.stop();
        }
    }

    @Test
    void noStatsFrameWhileNothingArrives() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);

        assertThat(pacer.statsDue(T0 + 5_000, 0)).isFalse();
    }

    @Test
    void statsAtMostOnceASecondAndOnlyForWhatArrivedSince() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);
        assertThat(pacer.statsDue(T0 + 250, T0 + 5)).as("within the second of the opening frame").isFalse();
        assertThat(pacer.statsDue(T0 + 1_000, T0 + 5)).isTrue();
        assertThat(pacer.secondsSinceStats(T0 + 1_000)).isEqualTo(1.0);

        pacer = pacer.statsSent(T0 + 10);
        assertThat(pacer.statsDue(T0 + 500, T0 + 400)).as("within the second").isFalse();
        assertThat(pacer.statsDue(T0 + 1_010, T0 + 400)).isTrue();
        assertThat(pacer.statsDue(T0 + 1_010, T0 + 10)).as("nothing new since the last frame").isFalse();
        assertThat(pacer.secondsSinceStats(T0 + 2_010)).isEqualTo(2.0);
    }

    @Test
    void aKeepaliveAfterFifteenQuietSecondsAndAStatsFrameCountsAsSpeech() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);
        assertThat(pacer.keepaliveDue(T0 + 14_999)).isFalse();
        assertThat(pacer.keepaliveDue(T0 + 15_000)).isTrue();

        pacer = pacer.statsSent(T0 + 10_000);
        assertThat(pacer.keepaliveDue(T0 + 15_000)).isFalse();
        assertThat(pacer.keepaliveSent(T0 + 25_000).keepaliveDue(T0 + 30_000)).isFalse();
    }

    @Test
    void theRatesAreWhatArrivedPerSecondAndNeverNegative() {
        EventsApi.Counts before = new EventsApi.Counts(100, 10, 50, 0);
        EventsApi.Counts after = new EventsApi.Counts(300, 20, 60, 2);

        JsonObject stats = EventsApi.stats(after, before, 2.0, T0);
        assertThat(stats.getLong("at")).isEqualTo(T0);
        assertThat(stats.getLong("spans")).isEqualTo(300);
        assertThat(stats.getLong("droppedSpans")).isEqualTo(2);
        assertThat(stats.getObject("perSecond").getDouble("spans")).isEqualTo(100.0);
        assertThat(stats.getObject("perSecond").getDouble("logs")).isEqualTo(5.0);

        JsonObject cleared = EventsApi.stats(new EventsApi.Counts(0, 0, 0, 0), after, 1.0, T0);
        assertThat(cleared.getObject("perSecond").getDouble("spans")).as("a clear in between").isZero();
    }
}
