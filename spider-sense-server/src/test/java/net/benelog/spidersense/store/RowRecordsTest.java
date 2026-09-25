package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;

/**
 * The row records' JSON form (cli.adoc#export-import): what the export writes of
 * a row, the importer reads back as the same row, key for key, and what a
 * document leaves out reads as what the import has always stored for it.
 */
class RowRecordsTest {

    /** The row through its JSON and through the text of it, as a file carries it. */
    private static Json.JsonObject roundTrip(Json.JsonObject json) {
        return Json.parse(json.toJson()).asObject();
    }

    @Test
    void aLogLineReadsBackAsTheSameLine() {
        LogRow row = LogRow.of(new LogRecord(0, 1_000, "orders", "WARN", 13, "slow", "orders.Repo",
                "a".repeat(32), "b".repeat(16), Map.of("thread", "main")));

        assertThat(LogRow.fromJson(roundTrip(row.toJson()))).isEqualTo(row);
    }

    @Test
    void aLogLineIsTheSameLineByItsCutBodyAndLogger() {
        LogRow row = LogRow.fromJson(Json.obj().put("service", "orders").put("atMs", 5)
                .put("body", "x".repeat(Columns.LOG_BODY + 10)).put("severityNumber", 9));

        assertThat(row.sameLine()).containsExactly("orders", 5L, 9L, "x".repeat(Columns.LOG_BODY), null, null,
                null);
        assertThat(LogRow.fromJson(Json.obj()).body()).as("a missing body is empty, as the column needs").isEmpty();
    }

    @Test
    void aTingleReadsBackAsTheSameTingle() {
        TingleRow row = TingleRow.of(new Tingle(Tingle.SLOW_REQUEST, 1_000, "orders", "/orders", "1,532 ms",
                "a".repeat(32), "b".repeat(16), 1532.5));

        assertThat(TingleRow.fromJson(roundTrip(row.toJson()))).isEqualTo(row);
        assertThat(row.sameTingle()).containsExactly(1_000L, Tingle.SLOW_REQUEST, "orders", "/orders", "1,532 ms",
                "a".repeat(32), "b".repeat(16));
    }

    @Test
    void aStoredNullDurationIsExportedAsNull() {
        TingleRow row = new TingleRow(1, "error", "orders", "t", "d", null, null, Double.NaN);

        assertThat(row.toJson().get("durationMs").isNull()).isTrue();
    }

    @Test
    void aCatalogRowReadsBackAndIndexesThatAreNotJsonTravelAsText() {
        CatalogRow row = CatalogRow.of(new Batch.Catalog("orders", "PUBLIC", "ORDERS", "H2",
                "[{\"name\":\"PK\",\"unique\":true,\"columns\":[\"ID\"]}]", 1_000));
        assertThat(CatalogRow.fromJson(roundTrip(row.toJson()))).isEqualTo(row);
        assertThat(row.toJson().get("indexes")).isInstanceOf(Json.JsonArray.class);

        CatalogRow broken = new CatalogRow("orders", "", "ORDERS", null, "[not json", 1_000);
        assertThat(broken.toJson().getString("indexes")).isEqualTo("[not json");
        assertThat(CatalogRow.fromJson(roundTrip(broken.toJson()))).isEqualTo(broken);
        assertThat(CatalogRow.fromJson(Json.obj().put("service", "orders"))).as("no table").isNull();
    }

    @Test
    void aHistogramPointReadsBackWithItsBuckets() {
        PointRow row = PointRow.of(7, MetricPoint.histogram(1_000, 3, 6.0, 1.0, 3.0,
                new long[] {1, 2, 0}, new double[] {1.0, 5.0}));

        assertThat(row.buckets()).isEqualTo("{\"bounds\":[1.0,5.0],\"counts\":[1,2,0]}");
        PointRow back = PointRow.fromJson(roundTrip(row.toJson()));
        assertThat(back).usingRecursiveComparison().ignoringFields("buckets").isEqualTo(row);
        assertThat(Json.parse(String.valueOf(back.buckets())).toJson()).isEqualTo(Json.parse(String.valueOf(row.buckets())).toJson());
    }

    @Test
    void aGaugePointWithoutMinOrMaxExportsThemAsNullAndReadsThemBackAsNaN() {
        PointRow row = new PointRow(7, 1_000, 42.0, 0L, 0, Double.NaN, Double.NaN, null);

        Json.JsonObject json = row.toJson();

        assertThat(json.get("min").isNull()).isTrue();
        assertThat(json.get("buckets").isNull()).isTrue();
        assertThat(PointRow.fromJson(roundTrip(json))).isEqualTo(row);
        assertThat(PointRow.fromJson(json).inSeries(9).seriesId()).isEqualTo(9);
    }

    @Test
    void anInstrumentAndASeriesReadBack() {
        MetricRow metric = new MetricRow("orders", "http.server.request.duration", "histogram", "s",
                "Duration", false, "CUMULATIVE");
        assertThat(MetricRow.fromJson(roundTrip(metric.toJson()))).isEqualTo(metric);
        assertThat(MetricRow.fromJson(Json.obj().put("service", "orders").put("name", "m")).type())
                .as("a missing type is a gauge").isEqualTo("gauge");

        SeriesRow series = new SeriesRow(7, "orders", "jvm.memory.used", "{\"pool\":\"heap\",\"area\":\"x\"}");
        assertThat(SeriesRow.fromJson(roundTrip(series.toJson()))).isEqualTo(series);
        assertThat(series.sortedAttributes()).isEqualTo("{\"area\":\"x\",\"pool\":\"heap\"}");
    }

    @Test
    void aServiceReadsBackAndItsLastSightingDefaultsToItsFirst() {
        ServiceRow service = ServiceRow.of(new Batch.Sighting("orders",
                Map.of("telemetry.sdk.language", "java", "process.pid", 4242L), 1_000));
        assertThat(service.language()).isEqualTo("java");
        assertThat(service.pid()).isEqualTo(4242L);
        assertThat(ServiceRow.fromJson(roundTrip(service.toJson()))).isEqualTo(service);

        ServiceRow partial = ServiceRow.fromJson(Json.obj().put("name", "orders").put("firstSeen", 5));
        assertThat(partial).isEqualTo(new ServiceRow("orders", null, null, 5, 5, AttrJson.EMPTY_OBJECT));
    }

    @Test
    void aMarkReadsBackAndItsNoteIsStoredCut() {
        MarkRow mark = new MarkRow(1_000, "before", null, "n".repeat(2000));

        assertThat(MarkRow.fromJson(roundTrip(mark.toJson()))).isEqualTo(mark);
        assertThat(mark.storedNote()).hasSize(Columns.MARK_NOTE);
        assertThat(List.of(mark.toJson().get("service").isNull())).containsExactly(true);
    }
}
