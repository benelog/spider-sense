package net.benelog.spidersense.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * {@code spidersense.ingest.max-spans-per-second}: what protects the file from a
 * load test (storage.adoc#ingest-cap).
 *
 * <p>It counts the spans accepted in the current wall-clock second. Once that
 * count is over the cap, a span is kept only when its trace is one of the last
 * {@value #REMEMBERED_TRACES} traces something was accepted for; anything else is
 * dropped and counted. So what survives a burst is whole traces rather than a
 * sample of spans from all of them, and a trace on screen is never missing the
 * half of itself that arrived a moment later.
 *
 * <p>Logs and metric points are never dropped: they are a fraction of the volume
 * and losing one is losing the sentence that explains a trace.
 *
 * <p>Called from the OTLP request threads, so the decision is one short
 * {@code synchronized} block; the dropped count is read from the API thread.
 */
public final class IngestCap {

    /** The window of traces a burst may still add spans to. */
    private static final int REMEMBERED_TRACES = 10_000;

    private final @Nullable Long maxSpansPerSecond;
    private final LongSupplier clock;
    private final AtomicLong droppedSpans = new AtomicLong();

    /** Insertion-ordered and bounded: the oldest trace id falls out when the window is full. */
    private final LinkedHashMap<String, Boolean> accepted =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED_TRACES;
                }
            };

    private long second = Long.MIN_VALUE;
    private long countThisSecond;

    public IngestCap(@Nullable Long maxSpansPerSecond, LongSupplier clock) {
        this.maxSpansPerSecond = maxSpansPerSecond == null || maxSpansPerSecond <= 0
                ? null
                : maxSpansPerSecond;
        this.clock = clock;
    }

    /** The cap as configured, on the system clock. */
    public static IngestCap of(@Nullable Long maxSpansPerSecond) {
        return new IngestCap(maxSpansPerSecond, System::currentTimeMillis);
    }

    /** No cap at all: every span is accepted and nothing is ever counted. */
    public static IngestCap none() {
        return of(null);
    }

    /** What {@code /api/status.ingest.maxSpansPerSecond} reports; null when unset. */
    public @Nullable Long maxSpansPerSecond() {
        return maxSpansPerSecond;
    }

    /**
     * Whether this span may be written.
     *
     * <p>"Over the cap" is the count <em>after</em> counting this span: with a cap
     * of 10, the tenth span of a second is accepted and the eleventh of a trace
     * nobody has seen is not.
     */
    public boolean accept(String traceId) {
        if (maxSpansPerSecond == null) {
            return true;
        }
        synchronized (this) {
            long now = Math.floorDiv(clock.getAsLong(), 1000L);
            if (now != second) {
                second = now;
                countThisSecond = 0;
            }
            countThisSecond++;
            if (countThisSecond > maxSpansPerSecond && !accepted.containsKey(traceId)) {
                countThisSecond--;
                droppedSpans.incrementAndGet();
                return false;
            }
            accepted.put(traceId, Boolean.TRUE);
            return true;
        }
    }

    /** {@code /api/status.storage.droppedSpans}, and the SSE {@code stats} event. */
    public long droppedSpans() {
        return droppedSpans.get();
    }
}
