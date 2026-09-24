package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.json.Json;

/**
 * The seam the CLI uses: the same answers, from the database alone, with nothing
 * writing and nothing sweeping.
 */
class ReportsTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void readOnlyAnswersFromTheDatabaseWithNoServerRunning() {
        Config config = TestStore.config();
        try (Store store = new Store(config.jdbcUrl(), config.databaseFile(), config.retentionHours(),
                config.slowRequestMs(), config.slowQueryMs(), null)) {
            OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span("%032x".formatted(1), "%016x".formatted(1), "GET /orders/report",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                            Otlp.attr("http.request.method", "GET"),
                            Otlp.attr("http.route", "/orders/report"),
                            Otlp.attr("http.response.status_code", 200))));
            store.writer().awaitIdle(5_000);

            try (Reports reports = Reports.readOnly(config)) {
                Json.JsonObject status = reports.status("file", null, 0).json().asObject();
                assertThat(status.getString("mode")).isEqualTo("file");
                assertThat(status.get("endpoint").isNull()).isTrue();
                assertThat(status.getObject("counts").getLong("spans")).isEqualTo(1);
                assertThat(reports.status("file", null, 0).text()).startsWith("# status");
                assertThat(reports.status("file", null, 0).text())
                        .contains("| ignore | /actuator/**, /health, /healthz, /livez, /readyz |");

                Window window = Window.of(NOW - 60_000, NOW + 60_000);
                Json.JsonObject findings = reports.findings(window, null, 20, false).json().asObject();
                assertThat(findings.getArray("findings")).hasSize(1);
                assertThat(findings.getArray("findings").get(0).asObject().getString("kind"))
                        .isEqualTo("slow-endpoint");

                Marks.Mark mark = reports.mark("after-fix", "rebuilt", null);
                assertThat(mark.name()).isEqualTo("after-fix");
                assertThat(reports.marks(50).json().asObject().getArray("marks")).hasSize(1);
                assertThat(reports.selectors().resolve("after-fix", NOW, null)).isEqualTo(mark.at());

                assertThat(reports.check(window, null, null, java.util.Map.of()).json().asObject()
                        .getBoolean("pass")).isFalse();
                assertThat(reports.endpoints(window, null).text()).contains("GET /orders/report");

                Reports.Report sql = reports.sql(
                        "SELECT endpoint, http_status FROM span WHERE entry", 200, false);
                assertThat(sql.json().asObject().getLong("rowCount")).isEqualTo(1);
                assertThat(sql.text()).startsWith("# sql  1 rows");
                assertThat(sql.text()).contains("| GET /orders/report | 200 |");
                assertThatThrownBy(() -> reports.sql("DELETE FROM span", 200, false))
                        .isInstanceOf(ReadOnlyQuery.Refused.class);
            }
        }
    }

    /**
     * A regression, as the text and the JSON render it: first, severity high, the
     * state column reading regressed, and the resolution's instant as a time
     * (findings.adoc#resolutions).
     */
    @Test
    void aRegressionRendersFirstWithItsResolutionInTheNumbers() {
        Config config = TestStore.config();
        try (Store store = new Store(config.jdbcUrl(), config.databaseFile(), config.retentionHours(),
                config.slowRequestMs(), config.slowQueryMs(), null)) {
            OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
            decoder.accept(Otlp.traces(Otlp.service("orders"),
                    Otlp.span("%032x".formatted(1), "%016x".formatted(1), "GET /orders/report",
                            Span.SpanKind.SPAN_KIND_SERVER, NOW, 900,
                            Otlp.attr("http.request.method", "GET"),
                            Otlp.attr("http.route", "/orders/report"),
                            Otlp.attr("http.response.status_code", 200))));
            store.writer().awaitIdle(5_000);

            try (Reports reports = Reports.readOnly(config)) {
                Window window = Window.of(NOW - 60_000, NOW + 60_000);
                String id = reports.findings(window, null, 20, false).json().asObject()
                        .getArray("findings").get(0).asObject().getString("id");
                store.sql().update("MERGE INTO ack (finding_id, at_ms, note, resolved) KEY(finding_id)"
                        + " VALUES (?, ?, ?, TRUE)", java.util.List.of(id, NOW - 30_000, "precomputed"));

                Reports.Report report = reports.findings(window, null, 20, false);
                Json.JsonObject regression = report.json().asObject().getArray("findings").get(0).asObject();
                assertThat(regression.getString("kind")).isEqualTo("regression");
                assertThat(regression.getString("state")).isEqualTo("regressed");
                assertThat(regression.getObject("resolution").getString("note")).isEqualTo("precomputed");
                assertThat(regression.getObject("numbers").getString("originalKind"))
                        .isEqualTo("slow-endpoint");

                assertThat(report.text())
                        .contains("| # | severity | state | kind | id | service | title |")
                        .contains("| 1 | high | regressed | regression | " + id + " | orders | ")
                        .contains("1. " + id + " — came back after it was resolved (precomputed); ")
                        .contains("   resolvedAt " + Text.instantMillis(NOW - 30_000)
                                + ", note precomputed, originalKind slow-endpoint, calls 1");
                assertThat(reports.check(window, null, null, java.util.Map.of()).text())
                        .contains("| maxRegressions | 0 | 1 | fail | 1 finding: slow-endpoint GET /orders/report is slow |");
            }
        }
    }

    @Test
    void statusReportsTheRetentionCapTheIngestCapAndWhatTheCapDropped() {
        Config config = TestStore.config("--retention.spans=250000",
                "--ingest.max-spans-per-second=5000");
        try (Store store = new Store(config.jdbcUrl(), config.databaseFile(), config.retentionHours(),
                config.slowRequestMs(), config.slowQueryMs(), null,
                net.benelog.spidersense.store.IgnoredEndpoints.DEFAULT, config.retentionSpans(),
                net.benelog.spidersense.store.IngestCap.of(config.maxSpansPerSecond()))) {
            Reports reports = new Reports(config, store, config::port);

            Reports.Report status = reports.status("standalone", "http://127.0.0.1:4000", NOW);
            Json.JsonObject json = status.json().asObject();

            assertThat(json.getObject("retention").getLong("hours")).isEqualTo(24);
            assertThat(json.getObject("retention").getLong("spans")).isEqualTo(250_000);
            assertThat(json.getObject("ingest").getLong("maxSpansPerSecond")).isEqualTo(5_000);
            assertThat(json.getObject("storage").getLong("droppedSpans")).isZero();

            assertThat(status.text()).contains("| retention | 24 hours, 250000 spans |");
            assertThat(status.text()).contains("| ingest cap | 5000 spans/s |");
            assertThat(status.text()).contains("| dropped spans | 0 |");

            // One span a second is over a cap of 5000 only after 5000 of them, so drive the
            // cap itself: what matters here is that the number reaches the two renderings.
            for (int n = 0; n < 5_010; n++) {
                store.ingestCap().accept("%032x".formatted(n));
            }
            assertThat(store.droppedSpans()).isPositive();
            assertThat(reports.status("standalone", null, NOW).json().asObject()
                    .getObject("storage").getLong("droppedSpans")).isEqualTo(store.droppedSpans());
            assertThat(reports.status("standalone", null, NOW).text())
                    .contains("| dropped spans | " + store.droppedSpans() + " |");
        }
    }

    @Test
    void statusWithoutAnIngestCapSaysSoAndLeavesTheRowOut() {
        Config config = TestStore.config();
        try (Store store = new Store(config.jdbcUrl(), config.databaseFile(), config.retentionHours(),
                config.slowRequestMs(), config.slowQueryMs(), null)) {
            Reports.Report status = new Reports(config, store, config::port)
                    .status("standalone", null, NOW);

            assertThat(status.json().asObject().getObject("ingest").get("maxSpansPerSecond").isNull())
                    .isTrue();
            assertThat(status.text()).contains("| retention | 24 hours, 1000000 spans |");
            assertThat(status.text()).doesNotContain("ingest cap");
        }
    }
}
