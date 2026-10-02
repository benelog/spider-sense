package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingles;

/**
 * The trace as text (cli.adoc#trace-rendering) over hand-built spans whose shape
 * the instrumentation does not usually produce: a chain deeper than the call stack
 * goes.
 */
class TraceTextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final long BASE_NS = 1_700_000_000_000L * 1_000_000L;
    private static final Tingles TINGLES = new Tingles(500, 100);
    private static final CodeFrames FRAMES = new CodeFrames(null);

    private static SpanRecord span(String spanId, @Nullable String parent, String name, long offsetMs,
            long durationMs, Map<String, Object> attributes) {
        long start = BASE_NS + offsetMs * 1_000_000L;
        return new SpanRecord(TRACE, spanId, parent, "orders", name, "INTERNAL", start,
                start + durationMs * 1_000_000L, "UNSET", null, attributes, List.of(), "test");
    }

    private static Queries.TraceDetail trace(List<SpanRecord> spans) {
        return new Queries.TraceDetail(TRACE, 1_700_000_000_000L, 1_700_000_000_100L, 100.0,
                List.of("orders"), spans, List.of());
    }

    /** The span lines of a rendering: every line after the column header. */
    private static List<String> spanLines(String text) {
        List<String> lines = new ArrayList<>();
        boolean after = false;
        for (String line : text.split("\n", -1)) {
            if (after && !line.isEmpty()) {
                lines.add(line);
            }
            after |= line.startsWith("offset");
        }
        return lines;
    }

    @Test
    void aChainDeeperThanTheCallStackGoesIsRenderedAllTheSame() throws InterruptedException {
        int depth = 3_000;
        List<SpanRecord> spans = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            spans.add(span("%016x".formatted(i + 1), i == 0 ? null : "%016x".formatted(i), "level " + i,
                    i, depth - i, Map.of()));
        }

        String text = onASmallStack(() -> Text.trace(trace(spans), TINGLES, FRAMES, false));

        List<String> lines = spanLines(text);
        assertThat(lines).hasSize(depth);
        assertThat(lines.get(depth - 1)).endsWith("  ".repeat(depth - 1) + "INTERNAL level " + (depth - 1));
    }

    @Test
    void aSpanOnAParentCycleIsPrintedAsARoot() {
        Queries.TraceDetail trace = trace(List.of(
                span("0000000000000001", null, "root", 0, 50, Map.of()),
                span("0000000000000002", "0000000000000002", "own parent", 10, 10, Map.of()),
                span("0000000000000003", "0000000000000004", "one", 20, 10, Map.of()),
                span("0000000000000004", "0000000000000003", "other", 25, 5, Map.of())));

        assertThat(spanLines(Text.trace(trace, TINGLES, FRAMES, false))).containsExactly(
                "0.0 ms     50.0 ms   INTERNAL orders root",
                "10.0 ms    10.0 ms   INTERNAL orders own parent",
                "20.0 ms    10.0 ms   INTERNAL orders one",
                "25.0 ms    5.0 ms      INTERNAL other");
    }

    /** What {@code work} returns on a thread of 256 KiB stack, a quarter of a Jetty worker's. */
    private static <T> T onASmallStack(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }
}
