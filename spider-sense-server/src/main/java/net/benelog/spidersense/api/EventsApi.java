package net.benelog.spidersense.api;

import java.util.function.LongSupplier;

import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.store.Batch;
import net.benelog.spidersense.store.EventBus;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Tingle;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.SseStream;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;

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

    private void streamUntilClosed(SseStream stream, EventBus.Subscription subscription)
            throws InterruptedException {
        LongSupplier clock = store.clock();
        Pacer pacer = Pacer.start(clock.getAsLong());
        Counts previous = counts();
        stream.event("stats").send(stats(previous, previous, 1, clock.getAsLong()).toJson());

        while (stream.isOpen()) {
            EventBus.Event event = subscription.poll(POLL_MS);
            long now = clock.getAsLong();
            // The counts are read before the queue is emptied, and the ingest they are due for
            // before either. A flush queues its tingles before it marks the ingest, and commits
            // only an instant before it queues them, so the tingles of every trace a stats frame
            // counts go out ahead of it, and tail --until-traces never ends before the tingles
            // of the traces it waited for (cli.adoc#tail).
            Counts current = pacer.statsDue(now, store.events().lastIngestAt()) ? counts() : null;
            if (event != null) {
                send(stream, event);
            }
            for (EventBus.Event waiting : subscription.drain()) {
                send(stream, waiting);
            }
            if (current != null) {
                stream.event("stats").send(stats(current, previous, pacer.secondsSinceStats(now), now).toJson());
                previous = current;
                pacer = pacer.statsSent(now);
            }
            if (pacer.keepaliveDue(now)) {
                stream.comment("keepalive");
                pacer = pacer.keepaliveSent(now);
            }
        }
    }

    /**
     * When the stream owes a frame: a {@code stats} frame at most once a second and only when
     * something was ingested since the last one, and a keepalive after fifteen seconds with no
     * frame at all. The rule, apart from the stream and the clock it runs on.
     *
     * @param lastStats     when the last stats frame went out, the opening one included
     * @param lastKeepalive when the stream last said anything that keeps it open
     */
    record Pacer(long lastStats, long lastKeepalive) {

        /** The pacing after the opening stats frame, which went out at {@code now}. */
        static Pacer start(long now) {
            return new Pacer(now, now);
        }

        boolean statsDue(long now, long lastIngestAt) {
            return lastIngestAt > lastStats && now - lastStats >= STATS_INTERVAL_MS;
        }

        /** The seconds the stats frame due at {@code now} covers, which its rates divide by. */
        double secondsSinceStats(long now) {
            return Math.max(0.001, (now - lastStats) / 1000.0);
        }

        Pacer statsSent(long now) {
            return new Pacer(now, now);
        }

        boolean keepaliveDue(long now) {
            return now - lastKeepalive >= KEEPALIVE_MS;
        }

        Pacer keepaliveSent(long now) {
            return new Pacer(lastStats, now);
        }
    }

    private void send(SseStream stream, EventBus.Event event) {
        switch (event.payload()) {
            case Tingle tingle -> stream.event("tingle").send(Codecs.tingle(tingle).toJson());
            // The sighting that inserted the row, whose receipt is the row's first_seen.
            case Batch.Sighting service -> stream.event("service").send(Json.object()
                    .put("name", service.name())
                    .put("firstSeen", service.at())
                    .toJson());
            default -> {
                // An event kind the stream does not publish; nothing to send.
            }
        }
    }

    record Counts(long spans, long traces, long logs, long droppedSpans) {
    }

    private Counts counts() {
        return new Counts(queries.spanCount(), queries.traceCount(), queries.logCount(),
                store.droppedSpans());
    }

    /**
     * A {@code stats} frame: the counts, and what arrived per second since {@code previous}, never
     * below zero, since a clear in between makes the counts fall.
     */
    static JsonObject stats(Counts current, Counts previous, double seconds, long at) {
        return Json.object()
                .put("at", at)
                .put("spans", current.spans())
                .put("traces", current.traces())
                .put("logs", current.logs())
                .put("droppedSpans", current.droppedSpans())
                .put("perSecond", Json.object()
                        .put("spans", Codecs.round(Math.max(0, current.spans() - previous.spans()) / seconds))
                        .put("logs", Codecs.round(Math.max(0, current.logs() - previous.logs()) / seconds)));
    }
}
