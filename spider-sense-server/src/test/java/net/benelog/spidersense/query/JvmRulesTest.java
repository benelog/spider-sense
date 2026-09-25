package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.MetricSeriesNames;

/**
 * The rules over metric series, {@code gc-pause}, {@code heap-pressure},
 * {@code thread-growth} and {@code pool-exhausted}, as functions of synthetic arrays:
 * no store, no ingest.
 */
class JvmRulesTest {

    private static final double NaN = Double.NaN;
    private static final long MINUTE = 60_000;

    /** A histogram point: count, sum and max in the series' unit. */
    private static MetricPoint gc(long at, long count, double sum, double max) {
        return new MetricPoint(at, 0, count, sum, 0, max, null, null);
    }

    private static MetricQueries.SeriesData gcSeries(String unit, String temporality,
            MetricPoint... points) {
        return new MetricQueries.SeriesData("svc", MetricSeriesNames.GC_DURATION, "histogram", unit,
                false, temporality,
                Map.of(MetricSeriesNames.GC_NAME, "G1 Young Generation",
                        MetricSeriesNames.GC_ACTION, "end of minor GC"),
                List.of(points));
    }

    // --- gc-pause --------------------------------------------------------------

    @Test
    void aCumulativeMaxThatDoesNotRiseDoesNotNameTheInterval() {
        // 1.2 s was the longest collection since the JVM started, before the window: the
        // interval's one collection took 50 ms, so the worst is its mean, not the old max.
        Findings.Ranked ranked = Findings.gcPause("svc", gcSeries("s", "CUMULATIVE",
                gc(0, 10, 2.0, 1.2), gc(MINUTE, 11, 2.05, 1.2)), 1_000);

        assertThat(ranked).isNull();
    }

    @Test
    void aCumulativeMaxThatRisesIsTheLongestCollection() {
        Findings.Ranked ranked = Findings.gcPause("svc", gcSeries("s", "CUMULATIVE",
                gc(0, 10, 2.0, 0.3), gc(MINUTE, 12, 3.5, 1.2)), 1_000);

        assertThat(ranked).isNotNull();
        Findings.Finding finding = ranked.finding();
        assertThat(finding.kind()).isEqualTo(Findings.GC_PAUSE);
        assertThat(finding.severity()).isEqualTo(Findings.HIGH);
        assertThat(finding.numbers()).containsEntry("worstMs", 1_200.0)
                .containsEntry("collections", 2L)
                .containsEntry("at", MINUTE);
        assertThat(finding.subject().jvm()).isEqualTo("gc:G1 Young Generation");
        assertThat(ranked.impact()).isEqualTo(1_200.0);
    }

    @Test
    void aCounterResetCountsNoCollectionsAndNoShare() {
        // The process restarted between the two points: the count went down.
        Findings.Ranked ranked = Findings.gcPause("svc", gcSeries("s", "CUMULATIVE",
                gc(0, 500, 40.0, 0.2), gc(MINUTE, 3, 0.01, 0.005)), 1_000);

        assertThat(ranked).isNull();
    }

    @Test
    void aShareOfTheIntervalOverATenthIsAMediumPause() {
        // 7 s of collections in one minute, none of them longer than 100 ms.
        Findings.Ranked ranked = Findings.gcPause("svc", gcSeries("ms", "DELTA",
                gc(0, 70, 7_000, 100), gc(MINUTE, 70, 7_000, 100)), 1_000);

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().severity()).isEqualTo(Findings.MEDIUM);
        assertThat((double) ranked.finding().numbers().get("shareMax")).isCloseTo(7_000.0 / MINUTE,
                offset(1e-9));
        assertThat(ranked.finding().numbers()).containsEntry("worstMs", 100.0)
                .containsEntry("collections", 140L);
    }

    @Test
    void aDeltaPointsMaxIsTheLongestCollectionOfItsInterval() {
        Findings.Ranked ranked = Findings.gcPause("svc", gcSeries("s", "DELTA",
                gc(0, 1, 0.01, 0.01), gc(MINUTE, 1, 0.5, 0.5)), 500);

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("worstMs", 500.0)
                .containsEntry("at", MINUTE);
    }

    @Test
    void anEmptySeriesIsNoFinding() {
        assertThat(Findings.gcPause("svc", gcSeries("s", "CUMULATIVE"), 1_000)).isNull();
    }

    // --- heap-pressure ---------------------------------------------------------

    private static JvmView.Memory heap(double[] used, double[] limit) {
        long[] t = new long[used.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * MINUTE;
        }
        return new JvmView.Memory(t, used, new double[used.length], limit);
    }

    @Test
    void theHeapAtNinetyPercentOfItsLimitIsPressure() {
        Findings.Ranked ranked = Findings.heapPressure("svc",
                heap(new double[]{100, 900, 500}, new double[]{1_000, 1_000, 1_000}));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("usedMax", 900.0)
                .containsEntry("limit", 1_000.0)
                .containsEntry("ratioMax", 0.9)
                .containsEntry("at", MINUTE);
    }

    @Test
    void aHeapJustUnderNinetyPercentOrWithNoLimitIsNot() {
        assertThat(Findings.heapPressure("svc",
                heap(new double[]{899}, new double[]{1_000}))).isNull();
        assertThat(Findings.heapPressure("svc",
                heap(new double[]{990, 995}, new double[]{NaN, 0}))).isNull();
        // A limit series shorter than the used one leaves the rest unjudged.
        assertThat(Findings.heapPressure("svc",
                heap(new double[]{100, 995}, new double[]{1_000}))).isNull();
    }

    // --- thread-growth ---------------------------------------------------------

    private static JvmView.Threads threads(double... counts) {
        long[] t = new long[counts.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * MINUTE;
        }
        return new JvmView.Threads(t, counts, new double[counts.length]);
    }

    @Test
    void threadsThatDoubleFromTwentyGrew() {
        Findings.Ranked ranked = Findings.threadGrowth("svc", threads(20, 30, 40));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("first", 20L)
                .containsEntry("last", 40L)
                .containsEntry("max", 40L);
        assertThat(ranked.impact()).isEqualTo(20.0);
    }

    @Test
    void threadsThatDoubleFromNineteenDidNot() {
        assertThat(Findings.threadGrowth("svc", threads(19, 38))).isNull();
    }

    @Test
    void fiftyMoreThreadsGrewWhateverTheStart() {
        assertThat(Findings.threadGrowth("svc", threads(100, 149))).isNull();
        assertThat(Findings.threadGrowth("svc", threads(100, 150))).isNotNull();
    }

    @Test
    void theFirstAndLastAreTheFirstAndLastReportedPoints() {
        Findings.Ranked ranked = Findings.threadGrowth("svc", threads(NaN, 20, 80, 45, NaN));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("first", 20L)
                .containsEntry("last", 45L)
                .containsEntry("max", 80L)
                .containsEntry("at", 3 * MINUTE);
        assertThat(Findings.threadGrowth("svc", threads(NaN, NaN))).isNull();
    }

    // --- pool-exhausted --------------------------------------------------------

    private static JvmView.ConnectionPool pool(double[] used, double[] max, double[] pending) {
        long[] t = new long[used.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * MINUTE;
        }
        return new JvmView.ConnectionPool("HikariPool-1", t, used, new double[used.length], max,
                pending);
    }

    @Test
    void aPoolWithNoLimitsIsExhaustedWhenSomethingWaited() {
        Findings.Ranked ranked = Findings.exhausted("svc", pool(new double[]{3, 10, 4},
                new double[]{NaN, NaN, NaN}, new double[]{NaN, 2, 0}));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("max", null)
                .containsEntry("usedMax", 10.0)
                .containsEntry("pendingMax", 2.0)
                .containsEntry("at", MINUTE);
        assertThat(ranked.finding().why()).contains("of its maximum");
    }

    @Test
    void aPoolWithNoLimitsAndNobodyWaitingIsNot() {
        assertThat(Findings.exhausted("svc", pool(new double[]{3, 10},
                new double[]{NaN, NaN}, new double[]{NaN, NaN}))).isNull();
    }

    @Test
    void aFullPoolIsExhaustedEvenWithNobodyWaiting() {
        Findings.Ranked ranked = Findings.exhausted("svc", pool(new double[]{5, 10, 7},
                new double[]{10, 10, 10}, new double[]{0, 0, 0}));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("max", 10.0)
                .containsEntry("usedMax", 10.0)
                .containsEntry("pendingMax", 0.0)
                .containsEntry("at", MINUTE);
    }

    @Test
    void theWorstPointIsTheMostWaitingThenTheFullest() {
        Findings.Ranked ranked = Findings.exhausted("svc", pool(new double[]{10, 8, 10},
                new double[]{10, 10, 10}, new double[]{1, 3, 3}));

        assertThat(ranked).isNotNull();
        assertThat(ranked.finding().numbers()).containsEntry("pendingMax", 3.0)
                .containsEntry("at", 2 * MINUTE);
    }
}
