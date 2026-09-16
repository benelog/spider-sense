package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TinglesTest {

    private final Tingles tingles = new Tingles(500, 100);

    private static SpanRecord span(String kind, String parent, long durationMs,
            Map<String, Object> attributes, List<SpanRecord.SpanEvent> events) {
        return new SpanRecord("a".repeat(32), "b".repeat(16), parent, "orders", "GET /orders/report",
                kind, 1_000_000_000L, 1_000_000_000L + durationMs * 1_000_000L, "UNSET", null,
                attributes, events, "scope");
    }

    @Test
    void anEntrySpanOverTheThresholdIsASlowRequest() {
        List<Tingle> raised = tingles.of(span("SERVER", null, 1532, Map.of("http.route", "/orders/report"),
                List.of()));

        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).kind()).isEqualTo(Tingle.SLOW_REQUEST);
        assertThat(raised.get(0).detail()).isEqualTo("1,532 ms");
        assertThat(raised.get(0).title()).isEqualTo("/orders/report");
    }

    @Test
    void anEntrySpanUnderTheThresholdIsNothing() {
        assertThat(tingles.of(span("SERVER", null, 499, Map.of(), List.of()))).isEmpty();
    }

    @Test
    void aSlowDatabaseSpanIsASlowQueryWithTheStatementAsItsDetail() {
        List<Tingle> raised = tingles.of(span("CLIENT", "c".repeat(16), 240,
                Map.of("db.system", "h2", "db.statement", "select * from orders where name like ?"),
                List.of()));

        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).kind()).isEqualTo(Tingle.SLOW_QUERY);
        assertThat(raised.get(0).detail()).isEqualTo("select * from orders where name like ?");
    }

    @Test
    void aFastDatabaseSpanIsNothing() {
        assertThat(tingles.of(span("CLIENT", "c".repeat(16), 99,
                Map.of("db.system", "h2", "db.statement", "select 1"), List.of()))).isEmpty();
    }

    @Test
    void anErrorTingleCarriesTypeAndMessage() {
        List<Tingle> raised = tingles.of(span("SERVER", null, 10, Map.of(),
                List.of(new SpanRecord.SpanEvent("exception", 0, Map.of(
                        "exception.type", "java.lang.IllegalStateException",
                        "exception.message", "Order 42 is already shipped")))));

        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).kind()).isEqualTo(Tingle.ERROR);
        assertThat(raised.get(0).detail())
                .isEqualTo("java.lang.IllegalStateException: Order 42 is already shipped");
    }

    @Test
    void anErrorThatOnlyPropagatedUpDoesNotTingleTwice() {
        // A non-entry span marked ERROR without an exception of its own is the frame
        // a failure passed through, not the place it happened.
        SpanRecord propagated = new SpanRecord("a".repeat(32), "b".repeat(16), "c".repeat(16), "orders",
                "internal", "INTERNAL", 0, 1_000_000L, "ERROR", "boom", Map.of(), List.of(), "scope");

        assertThat(tingles.of(propagated)).isEmpty();
    }

    @Test
    void oneSpanCanRaiseBothASlowQueryAndAnError() {
        List<Tingle> raised = tingles.of(span("SERVER", null, 900,
                Map.of("db.system", "h2", "db.statement", "select 1"),
                List.of(new SpanRecord.SpanEvent("exception", 0,
                        Map.of("exception.type", "java.sql.SQLException")))));

        assertThat(raised).extracting(Tingle::kind)
                .containsExactly(Tingle.SLOW_REQUEST, Tingle.SLOW_QUERY, Tingle.ERROR);
    }

    @Test
    void theSlowColumnMeansEitherThreshold() {
        assertThat(tingles.isSlow(span("SERVER", null, 600, Map.of(), List.of()))).isTrue();
        assertThat(tingles.isSlow(span("CLIENT", "c".repeat(16), 200,
                Map.of("db.system", "h2", "db.statement", "select 1"), List.of()))).isTrue();
        assertThat(tingles.isSlow(span("CLIENT", "c".repeat(16), 200, Map.of(), List.of()))).isFalse();
    }

    @Test
    void messagesAreNormalisedSoLiteralsDoNotSplitAGroup() {
        assertThat(Ids.normaliseMessage("Order 42 is already shipped"))
                .isEqualTo("Order ? is already shipped");
        assertThat(Ids.normaliseMessage("No row for 'ABC-123'"))
                .isEqualTo("No row for '?'");
        assertThat(Ids.errorId("orders", "X", Ids.normaliseMessage("Order 42 is shipped")))
                .isEqualTo(Ids.errorId("orders", "X", Ids.normaliseMessage("Order 43 is shipped")));
    }
}
