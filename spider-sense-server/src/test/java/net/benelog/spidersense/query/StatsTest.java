package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The copies of a group with one component replaced, with no store. */
class StatsTest {

    private final Stats.QueryStats query = new Stats.QueryStats("q1", "orders", "h2", "db", "SELECT",
            "orders", "select * from orders", 40, 1, 2.5, 2.0, 9.0, 12.0, 100.0, 3, List.of(), 1_000L,
            null);

    @Test
    void withCallersReplacesTheCallersAndNothingElse() {
        List<Stats.Caller> callers = List.of(new Stats.Caller("GET /orders", "orders", 40));

        Stats.QueryStats found = query.withCallers(callers);

        assertThat(found.callers()).isEqualTo(callers);
        assertThat(found.withCallers(List.of())).isEqualTo(query);
    }

    @Test
    void withSchemaReplacesTheSchemaAndNothingElse() {
        SchemaBlock block = new SchemaBlock(List.of(), List.of("orders.id"), List.of());

        Stats.QueryStats found = query.withSchema(block);

        assertThat(found.schema()).isSameAs(block);
        assertThat(found.withSchema(null)).isEqualTo(query);
    }

    @Test
    void withDetailReplacesTheEndpointsAndTheSampleAndNothingElse() {
        Stats.ErrorGroup group = new Stats.ErrorGroup("e1", "orders", "java.lang.IllegalStateException",
                "boom", 3, 1_000L, 2_000L, List.of(), null);
        List<Stats.EndpointCount> endpoints = List.of(new Stats.EndpointCount("GET /orders", 3));
        Stats.ErrorSample sample = new Stats.ErrorSample("t1", "s1", 2_000L, "boom", null);

        Stats.ErrorGroup detailed = group.withDetail(endpoints, sample);

        assertThat(detailed.endpoints()).isEqualTo(endpoints);
        assertThat(detailed.sample()).isEqualTo(sample);
        assertThat(detailed.withDetail(List.of(), null)).isEqualTo(group);
    }
}
