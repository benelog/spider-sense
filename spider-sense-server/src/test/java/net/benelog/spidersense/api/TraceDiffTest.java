package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingles;
import net.benelog.spidersilk.json.Json;

/**
 * Two traces aligned: the text of agent.md's Trace diff, byte for byte, and the
 * JSON beside it.
 *
 * <p>The fixtures are built by hand rather than ingested, because what is under
 * test is the rendering and the alignment: a group of 42 repeated siblings on one
 * side and one on the other, a span only in {@code a}, a span only in {@code b},
 * and a statement that only the slower side would have shown on its own.
 */
class TraceDiffTest {

    private static final String A = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String B = "09e96c4c6db157e690716c2615ffd146";

    private static final long BASE_NS = 1_700_000_000_000L * 1_000_000L;

    private static final String PRODUCT = "select p.id from product p where p.id = ?";
    private static final String CUSTOMER = "select c.id from customer c where c.id = ?";

    private static final Tingles TINGLES = new Tingles(500, 100);
    private static final CodeFrames FRAMES = new CodeFrames(null);

    // --- fixtures -------------------------------------------------------------

    private static SpanRecord span(String traceId, String spanId, String parent, String name,
            String kind, long offsetMs, double durationMs, Map<String, Object> attributes) {
        long start = BASE_NS + offsetMs * 1_000_000L;
        return new SpanRecord(traceId, spanId, parent, "spring-orders", name, kind, start,
                start + (long) (durationMs * 1_000_000L), "UNSET", null, attributes, List.of(),
                "test");
    }

    private static Map<String, Object> http() {
        return Map.of("http.request.method", "GET", "http.route", "/orders/{id}",
                "http.response.status_code", 200L);
    }

    private static Map<String, Object> db(String table, String statement) {
        return Map.of("db.system", "h2", "db.operation", "SELECT", "db.sql.table", table,
                "db.statement", statement);
    }

    /** The slow version: 42 repeats of one statement, and a call nobody makes afterwards. */
    private static Queries.TraceDetail before() {
        List<SpanRecord> spans = new ArrayList<>();
        spans.add(span(A, "0000000000000001", null, "GET /orders/{id}", "SERVER", 0, 300, http()));
        spans.add(span(A, "0000000000000002", "0000000000000001", "OrderRepository.findById",
                "INTERNAL", 1, 5, Map.of()));
        for (int i = 0; i < 42; i++) {
            spans.add(span(A, "%016x".formatted(100 + i), "0000000000000001", "SELECT product",
                    "CLIENT", 10 + i, 1, db("product", PRODUCT)));
        }
        spans.add(span(A, "0000000000000003", "0000000000000001", "SELECT customer", "CLIENT", 200,
                50, db("customer", CUSTOMER)));
        spans.add(span(A, "0000000000000004", "0000000000000001", "GET", "CLIENT", 260, 9,
                Map.of("url.full", "http://localhost:8081/api/books/155",
                        "http.request.method", "GET", "http.response.status_code", 200L)));
        return new Queries.TraceDetail(A, 1_700_000_000_000L, 1_700_000_000_300L, 300.0,
                List.of("spring-orders"), spans,
                List.of(new LogRecord(1, 1_700_000_000_100L, "spring-orders", "INFO", 9,
                        "loading order 42", "orders.OrderService", A, "0000000000000002",
                        Map.of())));
    }

    /** The fixed version: one statement, one lookup that was not there before. */
    private static Queries.TraceDetail after() {
        List<SpanRecord> spans = new ArrayList<>();
        spans.add(span(B, "0000000000000001", null, "GET /orders/{id}", "SERVER", 0, 40, http()));
        spans.add(span(B, "0000000000000002", "0000000000000001", "OrderRepository.findById",
                "INTERNAL", 1, 5, Map.of()));
        spans.add(span(B, "0000000000000100", "0000000000000001", "SELECT product", "CLIENT", 10, 2,
                db("product", PRODUCT)));
        spans.add(span(B, "0000000000000003", "0000000000000001", "SELECT customer", "CLIENT", 15,
                150, db("customer", CUSTOMER)));
        spans.add(span(B, "0000000000000005", "0000000000000001", "CacheLookup", "INTERNAL", 30, 1,
                Map.of()));
        return new Queries.TraceDetail(B, 1_700_000_000_000L, 1_700_000_000_040L, 40.0,
                List.of("spring-orders"), spans, List.of());
    }

    private static String diff(Queries.TraceDetail a, Queries.TraceDetail b, boolean full) {
        return Text.traceDiff(a, b, align(a, b, full));
    }

    private static List<Text.DiffLine> align(Queries.TraceDetail a, Queries.TraceDetail b,
            boolean full) {
        return Text.align(Text.lines(a, TINGLES, FRAMES, full), Text.lines(b, TINGLES, FRAMES, full));
    }

    // --- the text --------------------------------------------------------------

    @Test
    void theTwoTreesRenderAsOneTextWithAGutter() {
        String text = diff(before(), after(), false);

        assertThat(text).isEqualTo("""
                # trace diff 4bf92f3577b34da6a3ce929d0e0e4736 → 09e96c4c6db157e690716c2615ffd146 \
                 300.0 ms → 40.0 ms  (−260.0 ms, −86.7%)  46 → 5 spans

                   a          b          span
                =  300.0 ms   40.0 ms    SERVER spring-orders GET /orders/{id} → 200
                =  5.0 ms     5.0 ms       INTERNAL OrderRepository.findById
                -  42.0 ms    —            db SELECT product  × 42
                                             select p.id from product p where p.id = ?
                +  —          2.0 ms       db SELECT product  × 1
                =  50.0 ms    150.0 ms     db SELECT customer
                                             select c.id from customer c where c.id = ?
                -  9.0 ms     —            CLIENT GET http://localhost:8081/api/books/155 → 200
                +  —          1.0 ms       INTERNAL CacheLookup
                """);
    }

    @Test
    void theColumnsAreTheGutterAndTwoDurationsAsWideAsTheTraceRenderingUses() {
        List<String> lines = List.of(diff(before(), after(), false).split("\n", -1));

        assertThat(lines.get(2)).isEqualTo("   a          b          span");
        for (String line : lines.subList(3, lines.size() - 1)) {
            if (line.isEmpty()) {
                continue;
            }
            if ("=+-".indexOf(line.charAt(0)) >= 0) {
                assertThat(line.substring(1, 3)).as(line).isEqualTo("  ");
                assertThat(line.charAt(3)).as("a duration starts the a column of " + line)
                        .isNotEqualTo(' ');
                assertThat(line.charAt(14)).as("a duration starts the b column of " + line)
                        .isNotEqualTo(' ');
            } else {
                assertThat(line).as("a continuation carries no gutter")
                        .startsWith(" ".repeat(25 + 2));
            }
        }
    }

    @Test
    void theLogsOfNeitherTraceAreShown() {
        assertThat(diff(before(), after(), false)).doesNotContain("logs").doesNotContain("loading order");
    }

    @Test
    void theSameTraceTwiceIsAllEquals() {
        String text = diff(before(), before(), false);

        assertThat(text).startsWith("# trace diff " + A + " → " + A + "  300.0 ms → 300.0 ms"
                + "  (+0.0 ms, +0.0%)  46 → 46 spans");
        for (String line : text.split("\n")) {
            if (line.startsWith("+") || line.startsWith("-")) {
                throw new AssertionError("a trace differs from itself: " + line);
            }
        }
        assertThat(text).contains("=  42.0 ms    42.0 ms      db SELECT product  × 42");
    }

    @Test
    void fullExpandsTheCollapsedGroupsOnBothSidesBeforeAligning() {
        String text = diff(before(), after(), true);

        assertThat(text).doesNotContain("× 42");
        assertThat(text).contains("-  1.0 ms     —            db SELECT product");
        long product = text.lines().filter(line -> line.endsWith("db SELECT product")).count();
        assertThat(product).isEqualTo(42);
    }

    // --- the JSON ---------------------------------------------------------------

    @Test
    void theJsonCarriesTheSameLinesAndTheirCounts() {
        Queries.TraceDetail a = before();
        Queries.TraceDetail b = after();

        Json.JsonObject json = Codecs.traceDiff(a, b, align(a, b, false));

        assertThat(json.getString("a")).isEqualTo(A);
        assertThat(json.getString("b")).isEqualTo(B);
        assertThat(json.getObject("durationMs").getDouble("a")).isEqualTo(300.0);
        assertThat(json.getObject("durationMs").getDouble("b")).isEqualTo(40.0);
        assertThat(json.getObject("spans").getLong("a")).isEqualTo(46);
        assertThat(json.getObject("spans").getLong("b")).isEqualTo(5);

        Json.JsonArray lines = json.getArray("lines");
        assertThat(lines.size()).isEqualTo(7);

        Json.JsonObject root = lines.get(0).asObject();
        assertThat(root.getString("op")).isEqualTo("=");
        assertThat(root.getLong("depth")).isZero();
        assertThat(root.getString("category")).isEqualTo("http");
        assertThat(root.getString("summary")).isEqualTo("GET /orders/{id} → 200");
        assertThat(root.getDouble("aMs")).isEqualTo(300.0);
        assertThat(root.getDouble("bMs")).isEqualTo(40.0);
        assertThat(root.get("count").isNull()).isTrue();

        Json.JsonObject gone = lines.get(2).asObject();
        assertThat(gone.getString("op")).isEqualTo("-");
        assertThat(gone.getString("summary")).isEqualTo("SELECT product");
        assertThat(gone.getString("category")).isEqualTo("db");
        assertThat(gone.getDouble("aMs")).isEqualTo(42.0);
        assertThat(gone.get("bMs").isNull()).isTrue();
        assertThat(gone.getObject("count").getLong("a")).isEqualTo(42);
        assertThat(gone.getObject("count").get("b").isNull()).isTrue();

        Json.JsonObject added = lines.get(3).asObject();
        assertThat(added.getString("op")).isEqualTo("+");
        assertThat(added.getObject("count").get("a").isNull()).isTrue();
        assertThat(added.getObject("count").getLong("b")).isEqualTo(1);

        assertThat(lines.get(5).asObject().getString("op")).isEqualTo("-");
        assertThat(lines.get(6).asObject().getString("op")).isEqualTo("+");
        assertThat(lines.get(6).asObject().getString("summary")).isEqualTo("CacheLookup");
    }

    /** The key masks each run of digits, so the same request to two ids of different length is the same line. */
    @Test
    void aSummaryThatDiffersOnlyInItsDigitsIsTheSameLine() {
        Queries.TraceDetail a = before();
        List<SpanRecord> spans = new ArrayList<>(a.spans());
        spans.set(spans.size() - 1, span(B, "0000000000000004", "0000000000000001", "GET",
                "CLIENT", 260, 4,
                Map.of("url.full", "http://localhost:8081/api/books/87",
                        "http.request.method", "GET", "http.response.status_code", 200L)));
        Queries.TraceDetail b = new Queries.TraceDetail(B, a.start(), a.end(), a.durationMs(),
                a.services(), spans, List.of());

        String text = diff(a, b, false);

        assertThat(text).contains(
                "=  9.0 ms     4.0 ms       CLIENT GET http://localhost:8081/api/books/155 → 200");
    }
}
