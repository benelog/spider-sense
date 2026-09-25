package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;

/**
 * Which row a received span becomes, decided without a database
 * (storage.adoc#schema), and the row's JSON form (cli.adoc#export-import).
 */
class SpanRowTest {

    private final Tingles tingles = new Tingles(500, 100, IgnoredEndpoints.defaults());

    private static SpanRecord span(String kind, String name, long durationMs, String status,
            Map<String, Object> attributes, List<SpanRecord.SpanEvent> events) {
        return new SpanRecord("a".repeat(32), "b".repeat(16), null, "orders", name, kind,
                1_000_000_000L, 1_000_000_000L + durationMs * 1_000_000L, status, null, attributes, events, "scope");
    }

    @Test
    void aSlowRequestIsAnEntryWithItsEndpointAndNoError() {
        SpanRow row = SpanRow.of(span("SERVER", "GET /orders/{id}", 600, "UNSET",
                Map.of("http.request.method", "GET", "http.route", "/orders/{id}"), List.of()), tingles);

        assertThat(row.entry()).isTrue();
        assertThat(row.slow()).isTrue();
        assertThat(row.endpoint()).isEqualTo("GET /orders/{id}");
        assertThat(row.endpointId()).isEqualTo(Ids.endpointId("orders", "GET /orders/{id}"));
        assertThat(row.error()).isFalse();
        assertThat(row.errorType()).isNull();
        assertThat(row.errorId()).isNull();
        assertThat(row.queryId()).isNull();
        assertThat(row.startMs()).isEqualTo(1_000);
        assertThat(row.durationNs()).isEqualTo(600_000_000L);
    }

    @Test
    void anIgnoredEndpointIsNeitherAnEntryNorSlowAndHasNoEndpoint() {
        SpanRow row = SpanRow.of(span("SERVER", "GET /actuator/health", 900, "UNSET",
                Map.of("http.request.method", "GET", "http.route", "/actuator/health"), List.of()), tingles);

        assertThat(row.entry()).isFalse();
        assertThat(row.slow()).isFalse();
        assertThat(row.endpoint()).isNull();
        assertThat(row.endpointId()).isNull();
    }

    @Test
    void aDatabaseSpanHasItsQueryIdAndAFailedOneItsErrorColumns() {
        SpanRow row = SpanRow.of(span("CLIENT", "SELECT orders", 150, "ERROR",
                Map.of("db.system", "h2", "db.statement", "select * from orders"),
                List.of(new SpanRecord.SpanEvent("exception", 1_000_000_000L,
                        Map.of("exception.type", "java.sql.SQLException", "exception.message", "boom")))),
                tingles);

        assertThat(row.slow()).as("over the slow-query threshold").isTrue();
        assertThat(row.queryId()).isEqualTo(Ids.queryId("orders", "h2", "select * from orders"));
        assertThat(row.error()).isTrue();
        assertThat(row.errorType()).isEqualTo("java.sql.SQLException");
        assertThat(row.errorMessage()).isEqualTo("boom");
        assertThat(row.errorId()).isEqualTo(Ids.errorId("orders", "java.sql.SQLException", "boom", null));
    }

    @Test
    void aLongNameIsKeptWholeInTheRowAndCutWhenItIsBound() {
        SpanRow row = SpanRow.of(span("INTERNAL", "x".repeat(2000), 1, "UNSET", Map.of(), List.of()), tingles);

        assertThat(row.name()).hasSize(2000);
        assertThat(Columns.cut(row.name(), Columns.SPAN_NAME)).hasSize(Columns.SPAN_NAME);
    }

    @Test
    void theExportedRowReadsBackAsTheSameRow() {
        SpanRow row = SpanRow.of(span("SERVER", "GET /orders", 600, "ERROR",
                Map.of("http.request.method", "GET", "http.route", "/orders", "http.response.status_code", 500L),
                List.of()), tingles);

        Json.JsonObject json = row.toJson();

        assertThat(SpanRow.fromJson(json)).isEqualTo(row);
        assertThat(SpanRow.fromJson(Json.parse(json.toJson()).asObject())).isEqualTo(row);
        assertThat(json.get("attributes")).isInstanceOf(Json.JsonObject.class);
        assertThat(json.get("events")).isInstanceOf(Json.JsonArray.class);
        assertThat(json.getLong("httpStatus")).isEqualTo(500);
    }

    @Test
    void aMissingNumberIsItsFallbackAndAMissingTextStaysNull() {
        SpanRow row = SpanRow.fromJson(Json.obj().put("traceId", "t"));

        assertThat(row.traceId()).isEqualTo("t");
        assertThat(row.spanId()).isNull();
        assertThat(row.httpStatus()).isNull();
        assertThat(row.startMs()).isZero();
        assertThat(row.entry()).isFalse();
        assertThat(row.attributes()).isEqualTo(AttrJson.EMPTY_OBJECT);
        assertThat(row.events()).isEqualTo(AttrJson.EMPTY_ARRAY);
    }
}
