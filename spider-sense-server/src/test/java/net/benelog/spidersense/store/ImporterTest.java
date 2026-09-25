package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.json.Json;

/**
 * The importer of cli.adoc#export-import, against a live writer sharing its
 * database (storage.adoc#writer).
 *
 * <p>The import is one transaction on the calling thread, and it can run for
 * minutes; what it must not do meanwhile is hold a row the running writer needs.
 */
class ImporterTest {

    private static final long AT = 1_700_000_000_000L;
    private static final String TRACE = "%032x".formatted(42);

    @TempDir
    Path dir;

    private Database database;
    private Writer writer;

    @BeforeEach
    void open() {
        database = Database.open("jdbc:h2:" + dir.resolve("sense") + ";AUTO_SERVER=TRUE;NON_KEYWORDS=KEY,VALUE",
                dir.resolve("sense.mv.db"));
        writer = new Writer(database.sql(), new EventBus(),
                new Tingles(500, 100, IgnoredEndpoints.of(IgnoredEndpoints.DEFAULT)));
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** A flush of {@code service}: one span of a trace of its own, and the sighting. */
    private void flush(String service, int trace) {
        Batch batch = new Batch();
        batch.saw(new Batch.Sighting(service, Map.of("process.pid", 100L), AT));
        batch.add(new SpanRecord("%032x".formatted(trace), "%016x".formatted(1), null, service, "GET /orders",
                "SERVER", AT * 1_000_000L, AT * 1_000_000L + 5_000_000L, "UNSET", null, Map.of(), List.of(),
                "test"));
        writer.submit(batch);
        writer.flushNow();
    }

    /** A document of yesterday's session of {@code service}: one span, and the service row. */
    private static Json.JsonObject document(String service) {
        long yesterday = AT - 86_400_000L;
        return Json.obj()
                .put("spiderSense", Json.obj().put("schema", Schema.VERSION))
                .put("services", Json.arr().add(Json.obj().put("name", service)
                        .put("firstSeen", yesterday).put("lastSeen", yesterday + 60_000)))
                .put("spans", Json.arr().add(Json.obj()
                        .put("traceId", TRACE).put("spanId", "%016x".formatted(1)).put("service", service)
                        .put("name", "GET /orders").put("kind", "SERVER").put("startMs", yesterday)
                        .put("startNs", yesterday * 1_000_000L).put("durationNs", 5_000_000L)
                        .put("status", "UNSET").put("entry", true).put("error", false).put("slow", false)
                        .put("category", "http")));
    }

    private long count(String statement) {
        return database.sql().count(statement, List.of());
    }

    /**
     * The developer imports yesterday's session of the service that is running now.
     * While the import is under way, which here means waiting for a trace row another
     * connection holds, the running service's flushes must still be written, not
     * wait for the import's commit on the service row it merged.
     */
    @Test
    void aFlushOfTheRunningServiceIsNotHeldUpByAnImportOfIt() throws Exception {
        flush("orders", 1);
        Importer importer = writer.importer();

        try (Connection other = database.sql().connection()) {
            other.setAutoCommit(false);
            try (PreparedStatement hold = other.prepareStatement("MERGE INTO trace (trace_id, start_ms, end_ms,"
                    + " duration_ns, root_name, root_service, root_kind, services, span_count, error_count,"
                    + " db_count, slow, error) KEY(trace_id) VALUES (?, 0, 0, 0, '', '', '', '[]', 0, 0, 0,"
                    + " FALSE, FALSE)")) {
                hold.setString(1, TRACE);
                hold.executeUpdate();
            }
            var importing = CompletableFuture.supplyAsync(() -> importer.importDocument(document("orders")));
            Thread.sleep(300);
            assertThat(importing).as("the import waits for the trace row").isNotDone();

            var flushing = CompletableFuture.runAsync(() -> flush("orders", 2));
            flushing.get(1_500, TimeUnit.MILLISECONDS);

            other.rollback();
            assertThat(importing.get(10, TimeUnit.SECONDS).spans()).isEqualTo(1);
        }

        assertThat(count("SELECT COUNT(*) FROM span")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM trace")).isEqualTo(3);
        assertThat(count("SELECT first_seen FROM service WHERE name = 'orders'"))
                .as("widened to the imported session").isEqualTo(AT - 86_400_000L);
    }
}
