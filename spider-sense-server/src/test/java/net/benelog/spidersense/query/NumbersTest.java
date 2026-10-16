package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void onlyAPresentFiniteNumberIsFinite() {
        assertThat(Numbers.finite(1.5)).isTrue();
        assertThat(Numbers.finite(null)).isFalse();
        assertThat(Numbers.finite(Double.NaN)).isFalse();
        assertThat(Numbers.finite(Double.NEGATIVE_INFINITY)).isFalse();
    }

    @Test
    void whatIsNotFiniteIsADash() {
        assertThat(Numbers.millis(1532.44)).isEqualTo("1,532.4 ms");
        assertThat(Numbers.millis(Double.NaN)).isEqualTo("—");
        assertThat(Numbers.percent(0.43)).isEqualTo("43.0%");
        assertThat(Numbers.percent(null)).isEqualTo("—");
        assertThat(Numbers.score(Double.POSITIVE_INFINITY)).isEqualTo("—");
    }

    /** A value judged against a limit reads as the limit only when it is the limit. */
    @Test
    void aValueBesideItsLimitTakesTheDecimalsThatTellThemApart() {
        assertThat(Numbers.apart(1.04, 1, 1)).isEqualTo("1.04");
        assertThat(Numbers.apart(0.04, 0, 1)).isEqualTo("0.04");
        assertThat(Numbers.apart(0.8999, 0.9, 3)).isEqualTo("0.8999");
        assertThat(Numbers.apart(499.96, 500, 1)).as("a pass reads below its limit").isEqualTo("499.96");
        assertThat(Numbers.apart(1_202.5, 500, 1)).isEqualTo("1,202.5");
        assertThat(Numbers.apart(1, 1, 1)).as("the limit itself").isEqualTo("1.0");
        assertThat(Numbers.apart(Double.NaN, 1, 1)).isEqualTo("—");

        assertThat(Numbers.exactly(500, 0)).isEqualTo("500");
        assertThat(Numbers.exactly(2_000, 0)).isEqualTo("2,000");
        assertThat(Numbers.exactly(0.05, 1)).isEqualTo("0.05");
        assertThat(Numbers.exactly(0.9, 3)).isEqualTo("0.900");
    }
}
