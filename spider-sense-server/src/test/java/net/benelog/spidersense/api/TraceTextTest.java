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

    @Test
    void aSummaryWithNewlinesIsOneLineOfTheTree() {
        // A statement the agent named no table for, written as a text block: the summary is the
        // statement itself, newlines and all.
        String statement = """
                with recent as (
                  select * from orders where created_at > ?
                )
                select count(*) from recent""";
        Queries.TraceDetail trace = trace(List.of(
                span("0000000000000001", null, "GET /orders", 0, 50, Map.of()),
                span("0000000000000002", "0000000000000001", "query", 5, 10,
                        Map.of("db.system", "h2", "db.statement", statement)),
                span("0000000000000003", "0000000000000001", "line|break\nin a name", 16, 5, Map.of())));

        assertThat(spanLines(Text.trace(trace, TINGLES, FRAMES, false))).containsExactly(
                "0.0 ms     50.0 ms   INTERNAL orders GET /orders",
                "5.0 ms     10.0 ms     db with recent as ( select * from orders where created_at > ? )"
                        + " select count(*) from recent",
                "16.0 ms    5.0 ms      INTERNAL line|break in a name");
    }

    /** A service name with a line break keeps the heading and the span line each on one line. */
    @Test
    void aServiceNameWithALineBreakSplitsNoLine() {
        SpanRecord root = new SpanRecord(TRACE, "0000000000000001", null, "line\nbreak svc", "GET /y",
                "SERVER", BASE_NS, BASE_NS + 10_000_000L, "UNSET", null, Map.of(), List.of(), "test");
        Queries.TraceDetail trace = new Queries.TraceDetail(TRACE, 1_700_000_000_000L, 1_700_000_000_010L,
                10.0, List.of("line\nbreak svc", "orders"), List.of(root), List.of());

        String text = Text.trace(trace, TINGLES, FRAMES, false);

        assertThat(text.lines().findFirst().orElseThrow()).contains("  10.0 ms  line break svc → orders  1 spans");
        assertThat(spanLines(text)).containsExactly("0.0 ms     10.0 ms   SERVER line break svc GET /y");
    }

    @Test
    void theDiffAlignsASummaryOnItsOneLineText() {
        Queries.TraceDetail a = trace(List.of(
                span("0000000000000001", null, "work", 0, 50, Map.of()),
                span("0000000000000002", "0000000000000001", "two\nlines", 5, 10, Map.of())));
        Queries.TraceDetail b = trace(List.of(
                span("0000000000000001", null, "work", 0, 40, Map.of()),
                span("0000000000000002", "0000000000000001", "two  lines", 5, 8, Map.of())));

        List<Text.DiffLine> lines = Text.align(Text.lines(a, TINGLES, FRAMES, false),
                Text.lines(b, TINGLES, FRAMES, false));

        assertThat(lines).extracting(Text.DiffLine::op).containsExactly('=', '=');
    }

    @Test
    void aStatementAndAnExceptionUnderASpanKeepTheirBars() {
        long start = BASE_NS + 5_000_000L;
        SpanRecord failed = new SpanRecord(TRACE, "0000000000000003", "0000000000000001", "orders",
                "limit", "INTERNAL", start, start + 1_000_000L, "ERROR", null, Map.of(),
                List.of(new SpanRecord.SpanEvent("exception", start, Map.of(
                        "exception.type", "java.lang.IllegalStateException",
                        "exception.message", "rate|limit\nhit"))),
                "test");
        Queries.TraceDetail trace = trace(List.of(
                span("0000000000000001", null, "GET /a", 0, 500, Map.of()),
                span("0000000000000002", "0000000000000001", "query", 10, 150,
                        Map.of("db.system", "h2", "db.statement", "select a || b from t")),
                failed));

        assertThat(Text.trace(trace, TINGLES, FRAMES, false))
                .contains("  select a || b from t\n")
                .contains("  exception IllegalStateException: rate|limit hit\n");
    }

    /** One of the six {@code SELECT product} spans of an N+1, failed when {@code failed} says so. */
    private static SpanRecord product(int n, long durationMs, boolean failed) {
        long start = BASE_NS + (10L + 10L * n) * 1_000_000L;
        Map<String, Object> attributes = Map.of("db.system", "h2", "db.statement",
                "select * from product where id=?", "db.operation", "SELECT", "db.sql.table", "product");
        return new SpanRecord(TRACE, "%016x".formatted(100 + n), "0000000000000001", "orders",
                "SELECT product", "CLIENT", start, start + durationMs * 1_000_000L,
                failed ? "ERROR" : "UNSET", null, attributes,
                failed ? List.of(new SpanRecord.SpanEvent("exception", start, Map.of(
                        "exception.type", "org.h2.jdbc.JdbcSQLTimeoutException",
                        "exception.message", "Timeout trying to lock table PRODUCT",
                        "exception.stacktrace", """
                                org.h2.jdbc.JdbcSQLTimeoutException: Timeout
                                \tat org.h2.message.DbException.get(DbException.java:1)
                                \tat orders.Repo.find(Repo.java:12)""")))
                        : List.of(),
                "test");
    }

    /**
     * A failed or a slow span among repeated siblings that are neither keeps a line of its own,
     * its exception or its statement under it, as the waterfall keeps a row for it; a run of
     * failed spans collapses, marked, with the first one's exception (cli.adoc#trace-rendering).
     */
    @Test
    void aFailedOrSlowSpanIsNeverFoldedIntoARunOfSpansThatAreNeither() {
        List<SpanRecord> lastFails = new ArrayList<>(List.of(span("0000000000000001", null, "GET /a", 0, 300, Map.of())));
        List<SpanRecord> thirdSlow = new ArrayList<>(lastFails);
        List<SpanRecord> allFail = new ArrayList<>(lastFails);
        for (int n = 0; n < 6; n++) {
            lastFails.add(product(n, 1, n == 5));
            thirdSlow.add(product(n, n == 2 ? 150 : 1, false));
            allFail.add(product(n, 1, true));
        }

        assertThat(spanLines(Text.trace(trace(lastFails), TINGLES, FRAMES, false)).subList(1, 6)).containsExactly(
                "10.0 ms    5.0 ms      db SELECT product  × 5, 1.0 ms avg, 5.0 ms total",
                "                         select * from product where id=?",
                "60.0 ms    1.0 ms      db SELECT product  [error]",
                "                         exception JdbcSQLTimeoutException: Timeout trying to lock table PRODUCT",
                "                         orders.Repo.find(Repo.java:12)");
        assertThat(spanLines(Text.trace(trace(thirdSlow), TINGLES, FRAMES, false)).subList(1, 8)).containsExactly(
                "10.0 ms    1.0 ms      db SELECT product",
                "20.0 ms    1.0 ms      db SELECT product",
                "30.0 ms    150.0 ms    db SELECT product  [slow]",
                "                         select * from product where id=?",
                "40.0 ms    1.0 ms      db SELECT product",
                "50.0 ms    1.0 ms      db SELECT product",
                "60.0 ms    1.0 ms      db SELECT product");
        assertThat(spanLines(Text.trace(trace(allFail), TINGLES, FRAMES, false)).subList(1, 5)).containsExactly(
                "10.0 ms    6.0 ms      db SELECT product  × 6, 1.0 ms avg, 6.0 ms total  [error]",
                "                         select * from product where id=?",
                "                         exception JdbcSQLTimeoutException: Timeout trying to lock table PRODUCT",
                "                         orders.Repo.find(Repo.java:12)");
    }

    /**
     * The store keeps {@value SpanRecord#MAX_STATEMENT} characters of a statement, so one that
     * long says it may be cut, {@code --full} included, rather than read as the whole statement.
     */
    @Test
    void aStatementTheStoreMayHaveCutSaysSo() {
        String statement = "select x from t where id in (" + "?,".repeat(1500) + "?)";
        String whole = "select x from t where id in (" + "?,".repeat(10) + "?)";
        Queries.TraceDetail trace = trace(List.of(
                span("0000000000000001", null, "GET /a", 0, 500, Map.of()),
                span("0000000000000002", "0000000000000001", "query", 10, 150,
                        Map.of("db.system", "h2", "db.statement", statement)),
                span("0000000000000003", "0000000000000001", "query", 200, 150,
                        Map.of("db.system", "h2", "db.statement", whole))));
        String stored = statement.substring(0, SpanRecord.MAX_STATEMENT);

        assertThat(Text.trace(trace, TINGLES, FRAMES, true))
                .contains("  " + stored + "… (cut at 2,000 characters)\n")
                .contains("  " + whole + "\n");
        assertThat(Text.trace(trace, TINGLES, FRAMES, false))
                .contains("  " + stored.substring(0, 200) + "… (cut at 2,000 characters)\n")
                .contains("  " + whole + "\n");
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
