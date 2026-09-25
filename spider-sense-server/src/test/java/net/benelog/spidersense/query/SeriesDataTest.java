package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.MetricQueries.SeriesData;
import net.benelog.spidersense.store.MetricPoint;

/** How a histogram series is read as intervals, the one reading gc-pause and the JVM page share. */
class SeriesDataTest {

    private static MetricPoint point(long at, long count, double sum, double max) {
        return new MetricPoint(at, 0, count, sum, 0, max, null, null);
    }

    private static SeriesData series(String unit, String temporality, MetricPoint... points) {
        return new SeriesData("svc", "jvm.gc.duration", "histogram", unit, false, temporality,
                Map.of(), List.of(points));
    }

    @Test
    void theStoredUnitWinsOverTheConventionsSeconds() {
        assertThat(series("s", "CUMULATIVE").toMillis()).isEqualTo(1000);
        assertThat(series("ms", "CUMULATIVE").toMillis()).isEqualTo(1);
        assertThat(series("", "CUMULATIVE").toMillis()).isEqualTo(1000);
    }

    @Test
    void anythingButDeltaIsCumulative() {
        assertThat(series("s", "CUMULATIVE").cumulative()).isTrue();
        assertThat(series("s", "").cumulative()).isTrue();
        assertThat(series("s", "DELTA").cumulative()).isFalse();
    }

    @Test
    void aCumulativeSeriesIsTheDifferenceOfConsecutivePoints() {
        List<SeriesData.Interval> intervals = series("s", "CUMULATIVE",
                point(1_000, 10, 2.0, 0.5), point(61_000, 12, 2.5, 0.5), point(121_000, 15, 4.0, 0.9))
                .intervals();

        assertThat(intervals).containsExactly(
                new SeriesData.Interval(61_000, 60_000, 2, 0.5, 0.5, false),
                new SeriesData.Interval(121_000, 60_000, 3, 1.5, 0.9, true));
    }

    @Test
    void aCounterThatWentDownIsARestartAndNoInterval() {
        List<SeriesData.Interval> intervals = series("s", "CUMULATIVE",
                point(1_000, 500, 40.0, 0.2), point(61_000, 3, 0.25, 0.1), point(121_000, 5, 0.5, 0.1))
                .intervals();

        assertThat(intervals).containsExactly(new SeriesData.Interval(121_000, 60_000, 2, 0.25, 0.1, false));
    }

    @Test
    void aDeltaPointIsItsOwnInterval() {
        List<SeriesData.Interval> intervals = series("ms", "DELTA",
                point(1_000, 4, 30, 12), point(61_000, 0, 0, 0)).intervals();

        assertThat(intervals).containsExactly(
                new SeriesData.Interval(1_000, 0, 4, 30, 12, true),
                new SeriesData.Interval(61_000, 60_000, 0, 0, 0, true));
    }
}
