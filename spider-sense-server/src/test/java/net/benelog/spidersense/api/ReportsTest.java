package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Marks;
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
            }
        }
    }
}
