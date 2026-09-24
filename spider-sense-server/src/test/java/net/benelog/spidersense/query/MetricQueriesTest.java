package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.store.MetricPoint;

/** A monotonic sum as a rate per second, as {@code rate=true} answers it (api.adoc#nulls). */
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
}
