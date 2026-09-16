package loadgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatsTest {

    @Test
    void emptyStatsReportZeroes() {
        Stats stats = new Stats();
        assertThat(stats.sent()).isZero();
        assertThat(stats.average()).isZero();
        assertThat(stats.percentile(95)).isZero();
        assertThat(stats.line()).contains("sent=0").contains("p95=0ms");
    }

    @Test
    void statusCodesLandInTheRightBucket() {
        Stats stats = new Stats();
        stats.record(200, 10);
        stats.record(201, 10);
        stats.record(404, 10);
        stats.record(409, 10);
        stats.record(500, 10);
        stats.recordFailure(10);

        assertThat(stats.sent()).isEqualTo(6);
        assertThat(stats.ok()).isEqualTo(2);
        assertThat(stats.clientErrors()).isEqualTo(2);
        assertThat(stats.serverErrors()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
    }

    @Test
    void percentileOverAFullRing() {
        Stats stats = new Stats();
        for (int i = 1; i <= Stats.RING; i++) {
            stats.record(200, i);
        }
        assertThat(stats.percentile(95)).isEqualTo(950);
        assertThat(stats.percentile(50)).isEqualTo(500);
        assertThat(stats.percentile(100)).isEqualTo(1000);
        assertThat(stats.average()).isEqualTo(500);
    }

    @Test
    void theRingKeepsOnlyTheLastThousandDurations() {
        Stats stats = new Stats();
        for (int i = 0; i < 500; i++) {
            stats.record(200, 5000);
        }
        for (int i = 1; i <= Stats.RING; i++) {
            stats.record(200, i);
        }

        assertThat(stats.sent()).isEqualTo(1500);
        assertThat(stats.sortedDurations()).hasSize(Stats.RING);
        // The 5000 ms entries have all been overwritten.
        assertThat(stats.percentile(100)).isEqualTo(1000);
        assertThat(stats.average()).isEqualTo(500);
    }

    @Test
    void percentileOverAPartiallyFilledRing() {
        Stats stats = new Stats();
        for (int i = 1; i <= 10; i++) {
            stats.record(200, i * 10);
        }
        assertThat(stats.sortedDurations()).hasSize(10);
        assertThat(stats.percentile(95)).isEqualTo(100);
        assertThat(stats.percentile(50)).isEqualTo(50);
        assertThat(stats.average()).isEqualTo(55);
    }
}
