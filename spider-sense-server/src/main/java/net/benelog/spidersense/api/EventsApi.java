package net.benelog.spidersense.api;

import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.store.EventBus;
import net.benelog.spidersense.store.ServiceInfo;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Tingle;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.SseStream;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * {@code GET /api/events}: the live stream the UI keeps open.
 *
 * <p>Tingles and first sightings are pushed as they happen, because they are the
 * events a person is waiting for. Counts are not: they change on every export, so
 * they are coalesced to at most one {@code stats} frame a second and only while
 * something is actually arriving. A quiet stream sends a comment every fifteen
 * seconds, which is what keeps Jetty's thirty-second idle timeout from cutting it.
 */
public final class EventsApi {

    private static final long POLL_MS = 250;
    private static final long STATS_INTERVAL_MS = 1000;
    private static final long KEEPALIVE_MS = 15_000;

    private final Store store;
    private final Queries queries;

    public EventsApi(Store store, Queries queries) {
        this.store = store;
        this.queries = queries;
    }

    public void register(App app) {
        app.get("/api/events", "Live tingles, counts and service sightings", this::events);
    }

    public WebResponse events(WebRequest req) {
        return WebResponse.sse(stream -> {
            try (EventBus.Subscription subscription = store.events().subscribe()) {
                streamUntilClosed(stream, subscription);
            } catch (SseStream.Closed closed) {
                // The browser navigated away; nothing to report.
            }
        });
    }

    private void streamUntilClosed(SseStream stream, EventBus.Subscription subscription) throws InterruptedException {
        long lastKeepalive = System.currentTimeMillis();
        long lastStats = 0;
        Counts previous = counts();
        stream.send("stats", stats(previous, previous, 1).toJson());

        while (stream.isOpen()) {
            EventBus.Event event = subscription.poll(POLL_MS);
            if (event != null) {
                send(stream, event);
            }
            long now = System.currentTimeMillis();
            if (store.events().lastIngestAt() > lastStats && now - lastStats >= STATS_INTERVAL_MS) {
                Counts current = counts();
                double seconds = lastStats == 0 ? 1 : Math.max(0.001, (now - lastStats) / 1000.0);
                stream.send("stats", stats(current, previous, seconds).toJson());
                previous = current;
                lastStats = now;
                lastKeepalive = now;
            }
            if (now - lastKeepalive >= KEEPALIVE_MS) {
                stream.comment("keepalive");
                lastKeepalive = now;
            }
        }
    }

    private void send(SseStream stream, EventBus.Event event) {
        switch (event.payload()) {
            case Tingle tingle -> stream.send("tingle", Codecs.tingle(tingle).toJson());
            case ServiceInfo service -> stream.send("service", Json.obj()
                    .put("name", service.name())
                    .put("firstSeen", service.firstSeen())
                    .toJson());
            default -> {
                // An event kind the stream does not publish; nothing to send.
            }
        }
    }

    private record Counts(long spans, long traces, long logs, long droppedSpans) {
    }

    private Counts counts() {
        return new Counts(queries.spanCount(), queries.traceCount(), queries.logCount(),
                store.droppedSpans());
    }

    private Json.JsonObject stats(Counts current, Counts previous, double seconds) {
        return Json.obj()
                .put("at", System.currentTimeMillis())
                .put("spans", current.spans())
                .put("traces", current.traces())
                .put("logs", current.logs())
                .put("droppedSpans", current.droppedSpans())
                .put("perSecond", Json.obj()
                        .put("spans", Codecs.round(Math.max(0, current.spans() - previous.spans()) / seconds))
                        .put("logs", Codecs.round(Math.max(0, current.logs() - previous.logs()) / seconds)));
    }
}
