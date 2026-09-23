package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SpanRecordTest {

    private static SpanRecord span(String name, String kind, Map<String, Object> attributes) {
        return new SpanRecord("a".repeat(32), "b".repeat(16), null, "orders", name, kind,
                1_000_000_000L, 1_200_000_000L, "UNSET", null, attributes, List.of(), "scope");
    }

    /** The same span with a parent, so the root half of the entry rule is out of the way. */
    private static SpanRecord child(String name, String kind) {
        return new SpanRecord("a".repeat(32), "b".repeat(16), "c".repeat(16), "orders", name, kind,
                1_000_000_000L, 1_200_000_000L, "UNSET", null, Map.of(), List.of(), "scope");
    }

    @Test
    void anHttpRouteNamesTheEndpoint() {
        SpanRecord span = span("GET /orders/{id}", "SERVER",
                Map.of("http.request.method", "GET", "http.route", "/orders/{id}"));

        assertThat(span.endpointName()).isEqualTo("GET /orders/{id}");
    }

    @Test
    void aServletWildcardRouteIsNotARouteAndTheSpanNameWins() {
        // Spring Boot's DispatcherServlet mapping is /*; every endpoint would collapse into one.
        for (String wildcard : List.of("/*", "/", "/api/*")) {
            SpanRecord span = span("GET /orders/42", "SERVER",
                    Map.of("http.request.method", "GET", "http.route", wildcard));

            assertThat(span.endpointName()).isEqualTo("GET /orders/42");
        }
    }

    @Test
    void anEndpointShowsItsRouteAndNeverTheServletMapping() {
        assertThat(SpanRecord.endpointRoute("GET", "/orders/{id}", "GET /orders/{id}"))
                .isEqualTo("/orders/{id}");
        // A framework above the servlet (Spider Silk) named the span, the servlet mapping stayed.
        assertThat(SpanRecord.endpointRoute("GET", "/*", "GET /books/{id}")).isEqualTo("/books/{id}");
        assertThat(SpanRecord.endpointRoute(null, null, "/books/{id}")).isEqualTo("/books/{id}");
        // Nothing that is a path: no route rather than a made-up one.
        assertThat(SpanRecord.endpointRoute("GET", "/*", "GET")).isNull();
        assertThat(SpanRecord.endpointRoute(null, null, "nightly-report")).isNull();
    }

    @Test
    void withoutARouteTheSpanNameIsAlreadyTheEndpoint() {
        SpanRecord span = span("GET", "SERVER", Map.of("http.request.method", "GET"));

        assertThat(span.endpointName()).isEqualTo("GET");
    }

    @Test
    void bothGenerationsOfDatabaseAttributesReadTheSame() {
        SpanRecord old = span("SELECT orders", "CLIENT", Map.of(
                "db.system", "h2", "db.statement", "select * from orders where id = ?",
                "db.name", "orders", "db.operation", "SELECT", "db.sql.table", "orders"));
        SpanRecord stable = span("SELECT orders", "CLIENT", Map.of(
                "db.system.name", "h2", "db.query.text", "select * from orders where id = ?",
                "db.namespace", "orders", "db.operation.name", "SELECT", "db.collection.name", "orders"));

        for (SpanRecord span : List.of(old, stable)) {
            assertThat(span.dbSystem()).isEqualTo("h2");
            assertThat(span.dbStatement()).isEqualTo("select * from orders where id = ?");
            assertThat(span.dbNamespace()).isEqualTo("orders");
            assertThat(span.dbOperation()).isEqualTo("SELECT");
            assertThat(span.dbTable()).isEqualTo("orders");
            assertThat(span.category()).isEqualTo("db");
            assertThat(span.summary()).isEqualTo("SELECT orders");
        }
    }

    @Test
    void bothGenerationsOfHttpAttributesReadTheSame() {
        SpanRecord old = span("GET", "SERVER", Map.of(
                "http.method", "GET", "http.status_code", 200L, "http.target", "/orders",
                "net.peer.name", "localhost", "net.peer.port", 8081L));
        SpanRecord stable = span("GET", "SERVER", Map.of(
                "http.request.method", "GET", "http.response.status_code", 200L, "url.path", "/orders",
                "server.address", "localhost", "server.port", 8081L));

        for (SpanRecord span : List.of(old, stable)) {
            assertThat(span.httpMethod()).isEqualTo("GET");
            assertThat(span.httpStatus()).isEqualTo(200L);
            assertThat(span.urlPath()).isEqualTo("/orders");
            assertThat(span.serverAddress()).isEqualTo("localhost");
            assertThat(span.serverPort()).isEqualTo(8081L);
            assertThat(span.category()).isEqualTo("http");
        }
    }

    @Test
    void anEntrySpanIsAServerAConsumerOrANonDatabaseClientRoot() {
        assertThat(span("x", "SERVER", Map.of()).isEntry()).isTrue();
        assertThat(span("x", "CONSUMER", Map.of()).isEntry()).isTrue();
        // No parent and no database: the trace starts at a request someone made.
        assertThat(span("GET", "CLIENT", Map.of("url.full", "http://localhost:8081/books"))
                .isEntry()).isTrue();
        assertThat(span("x", "PRODUCER", Map.of()).isEntry()).isTrue();

        // A seeder's INSERT is its own trace, but it is work, not a request.
        assertThat(span("INSERT orders", "CLIENT",
                Map.of("db.system", "h2", "db.statement", "insert into orders values (?)"))
                .isEntry()).isFalse();
        assertThat(span("INSERT orders", "CLIENT",
                Map.of("db.system.name", "h2", "db.query.text", "insert into orders values (?)"))
                .isEntry()).isFalse();
        // So is a root INTERNAL span: a scheduler's tick, a startup task.
        assertThat(span("seed", "INTERNAL", Map.of()).isEntry()).isFalse();

        assertThat(child("x", "CLIENT").isEntry()).isFalse();
        assertThat(child("x", "INTERNAL").isEntry()).isFalse();
        // A server span is an entry wherever it sits: the callee side of a remote call has a parent.
        assertThat(child("GET /books", "SERVER").isEntry()).isTrue();
    }

    @Test
    void errorsAreMergedFromStatusEventAndAttribute() {
        SpanRecord byStatus = new SpanRecord("a".repeat(32), "b".repeat(16), null, "orders", "x",
                "SERVER", 0, 1, "ERROR", "boom", Map.of(), List.of(), "scope");
        SpanRecord byAttribute = span("x", "SERVER", Map.of("error.type", "java.io.IOException"));
        SpanRecord byEvent = new SpanRecord("a".repeat(32), "b".repeat(16), null, "orders", "x",
                "SERVER", 0, 1, "UNSET", null, Map.of(),
                List.of(new SpanRecord.SpanEvent("exception", 0, Map.of(
                        "exception.type", "java.lang.IllegalStateException",
                        "exception.message", "Order 42 is already shipped",
                        "exception.stacktrace", "at Orders.ship"))),
                "scope");

        assertThat(byStatus.isError()).isTrue();
        assertThat(byStatus.errorMessage()).isEqualTo("boom");
        assertThat(byAttribute.isError()).isTrue();
        assertThat(byAttribute.errorType()).isEqualTo("java.io.IOException");
        assertThat(byEvent.isError()).isTrue();
        assertThat(byEvent.errorType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(byEvent.errorMessage()).isEqualTo("Order 42 is already shipped");
        assertThat(byEvent.stacktrace()).isEqualTo("at Orders.ship");
    }

    @Test
    void aStatementIsCutAtTwoThousandCharacters() {
        SpanRecord span = span("SELECT", "CLIENT",
                Map.of("db.system", "h2", "db.statement", "x".repeat(5000)));

        assertThat(span.dbStatement()).hasSize(SpanRecord.MAX_STATEMENT);
    }
}
