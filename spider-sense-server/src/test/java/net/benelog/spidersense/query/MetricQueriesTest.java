package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.benelog.spidersense.Otlp;
import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.store.Batch;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.Store;

/**
 * A monotonic sum as a rate per second, as {@code rate=true} answers it (api.adoc#nulls),
 * by the temporality of the service that exported it (storage.adoc#schema).
 */
class MetricQueriesTest {

    @Test
    void aCumulativeSumIsDifferencedAndHasNoRateForItsFirstPoint() {
        double[] rates = MetricQueries.rate(List.of(MetricPoint.number(0, 10),
                MetricPoint.number(10_000, 30), MetricPoint.number(20_000, 35)), false);

        assertThat(rates[0]).isNaN();
        assertThat(rates[1]).isEqualTo(2.0);
        assertThat(rates[2]).isEqualTo(0.5);
    }

    @Test
    void aCounterThatResetOnARestartHasNoRateAtTheReset() {
        // jvm.cpu.time: an hour of CPU, then the process restarted and counts from zero.
        double[] rates = MetricQueries.rate(List.of(MetricPoint.number(0, 3590),
                MetricPoint.number(10_000, 3600), MetricPoint.number(20_000, 0.5),
                MetricPoint.number(30_000, 1.5)), false);

        assertThat(rates[1]).isEqualTo(1.0);
        assertThat(rates[2]).isNaN();
        assertThat(rates[3]).isEqualTo(0.1);
    }

    @Test
    void aDeltaSumIsDividedRatherThanDifferenced() {
        double[] rates = MetricQueries.rate(List.of(MetricPoint.number(0, 5),
                MetricPoint.number(10_000, 20), MetricPoint.number(20_000, 10)), true);

        assertThat(rates[0]).isNaN();
        assertThat(rates[1]).isEqualTo(2.0);
        assertThat(rates[2]).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "service-b first: {0}")
    @ValueSource(booleans = {false, true})
    void aServiceReadsItsPointsByItsOwnTemporality(boolean serviceBFirst) {
        long now = 1_700_000_000_000L;
        try (Store store = new Store(Store.Settings.defaults(TestStore.memoryUrl()))) {
            OtlpDecoder decoder = new OtlpDecoder(store, () -> 4000);
            MetricQueries metrics = new MetricQueries(store.sql());
            Window window = Window.of(now - 60_000, now + 60_000);
            Runnable serviceB = () -> {
                decoder.ingest(Otlp.sum(Otlp.service("service-b"), "requests.total", "1", now, 5, true,
                        AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA));
                decoder.ingest(Otlp.sum(Otlp.service("service-b"), "requests.total", "1", now + 1000, 7,
                        true, AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA));
                store.writer().awaitIdle(5_000);
            };
            if (serviceBFirst) {
                serviceB.run();
            }
            decoder.ingest(Otlp.sum(Otlp.service("service-a"), "requests.total", "1", now, 100, true));
            decoder.ingest(Otlp.sum(Otlp.service("service-a"), "requests.total", "1", now + 1000, 110, true));
            store.writer().awaitIdle(5_000);
            if (!serviceBFirst) {
                serviceB.run();
            }

            MetricQueries.SeriesData a = metrics.series("requests.total", "service-a", Map.of(), window).get(0);
            MetricQueries.SeriesData b = metrics.series("requests.total", "service-b", Map.of(), window).get(0);

            assertThat(a.temporality()).isEqualTo("CUMULATIVE");
            assertThat(MetricQueries.rate(a.points(), "DELTA".equals(a.temporality()))[1]).isEqualTo(10.0);
            assertThat(b.temporality()).isEqualTo("DELTA");
            assertThat(MetricQueries.rate(b.points(), "DELTA".equals(b.temporality()))[1]).isEqualTo(7.0);
            assertThat(metrics.series("requests.total", null, Map.of(), window))
                    .extracting(MetricQueries.SeriesData::service, MetricQueries.SeriesData::temporality)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("service-a", "CUMULATIVE"),
                            org.assertj.core.groups.Tuple.tuple("service-b", "DELTA"));
        }
    }

    /** A series whose attributes are longer than their column is stored as JSON that reads back (storage.adoc#writer). */
    @Test
    void aSeriesWhoseAttributesOverflowTheirColumnIsStoredAsReadableJson() {
        long now = 1_700_000_000_000L;
        try (Store store = new Store(Store.Settings.defaults(TestStore.memoryUrl()))) {
            Batch batch = new Batch();
            batch.add(new Batch.MetricSample("orders", "app.requests", "gauge", "1", "", false, "UNSPECIFIED",
                    Map.of("http.target", "/" + "x".repeat(5_000), "http.method", "GET"),
                    MetricPoint.number(now, 1)));
            batch.add(new Batch.MetricSample("orders", "app.requests", "gauge", "1", "", false, "UNSPECIFIED",
                    Map.of("http.target", "/short", "http.method", "GET"), MetricPoint.number(now, 2)));
            store.writer().submit(batch);
            store.writer().awaitIdle(5_000);
            // The same long series again, as the next export would carry it: one series, two points.
            Batch again = new Batch();
            again.add(new Batch.MetricSample("orders", "app.requests", "gauge", "1", "", false, "UNSPECIFIED",
                    Map.of("http.target", "/" + "x".repeat(5_000), "http.method", "GET"),
                    MetricPoint.number(now + 1000, 3)));
            store.writer().submit(again);
            store.writer().awaitIdle(5_000);

            List<MetricQueries.SeriesData> series = new MetricQueries(store.sql())
                    .series("app.requests", "orders", Map.of(), Window.of(now - 60_000, now + 60_000));

            assertThat(series).hasSize(2);
            MetricQueries.SeriesData cut = series.stream()
                    .filter(one -> !"/short".equals(one.attributes().get("http.target"))).findFirst().orElseThrow();
            assertThat(cut.attributes().get("http.method")).isEqualTo("GET");
            assertThat((String) cut.attributes().get("http.target")).startsWith("/xxx").endsWith("…");
            assertThat(cut.points()).hasSize(2);
        }
    }

    /** A row an older Spider Sense stored cut at an arbitrary character costs its own series, not the answer. */
    @Test
    void aSeriesWhoseStoredAttributesDoNotParseIsLeftOut() {
        long now = 1_700_000_000_000L;
        try (Store store = new Store(Store.Settings.defaults(TestStore.memoryUrl()))) {
            Batch batch = new Batch();
            batch.add(new Batch.MetricSample("orders", "app.requests", "gauge", "1", "", false, "UNSPECIFIED",
                    Map.of("http.target", "/long"), MetricPoint.number(now, 1)));
            batch.add(new Batch.MetricSample("orders", "app.requests", "gauge", "1", "", false, "UNSPECIFIED",
                    Map.of("http.target", "/short"), MetricPoint.number(now, 2)));
            store.writer().submit(batch);
            store.writer().awaitIdle(5_000);
            store.sql().update("UPDATE metric_series SET attributes = '{\"http.target\":\"/lo'"
                    + " WHERE attributes LIKE '%/long%'", List.of());

            List<MetricQueries.SeriesData> series = new MetricQueries(store.sql())
                    .series("app.requests", "orders", Map.of(), Window.of(now - 60_000, now + 60_000));

            assertThat(series).extracting(one -> one.attributes().get("http.target")).containsExactly("/short");
        }
    }
}
