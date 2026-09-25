package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The grammar of "a span in the window", as SQL text and parameters, with no store. */
class WhereTest {

    private final Window window = Window.of(1_000, 2_000);

    @Test
    void theWindowIsBoundAndTheServiceIsOptional() {
        assertThat(Where.window(window, null).sql()).isEqualTo("start_ms BETWEEN ? AND ?");
        assertThat(Where.window(window, null).params()).containsExactly(1_000L, 2_000L);

        Where one = Where.window(window, "orders");
        assertThat(one.sql()).isEqualTo("start_ms BETWEEN ? AND ? AND service = ?");
        assertThat(one.params()).containsExactly(1_000L, 2_000L, "orders");
    }

    @Test
    void entriesAndJobsAddTheirPredicate() {
        assertThat(Where.entries(window, "orders").sql())
                .isEqualTo("start_ms BETWEEN ? AND ? AND service = ? AND entry");
        assertThat(Where.jobs(window, null).sql())
                .isEqualTo("start_ms BETWEEN ? AND ? AND parent_span_id IS NULL AND kind = 'INTERNAL'");
        assertThat(SpanSql.job("r.")).isEqualTo("r.parent_span_id IS NULL AND r.kind = 'INTERNAL'");
    }

    @Test
    void andInBindsEveryValueInOrder() {
        Where where = Where.window(window, null).andIn("query_id", List.of("a", "b", "c"))
                .and("error");

        assertThat(where.sql()).isEqualTo("start_ms BETWEEN ? AND ? AND query_id IN (?, ?, ?) AND error");
        assertThat(where.params()).containsExactly(1_000L, 2_000L, "a", "b", "c");
    }

    @Test
    void aThresholdInMillisecondsIsANanosecondLiteral() {
        assertThat(SpanSql.slowerThan(250)).isEqualTo("duration_ns > 250000000");
        assertThat(SpanSql.slowerThan(SpanSql.P95, 1))
                .isEqualTo("PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ns) > 1000000");
    }
}
