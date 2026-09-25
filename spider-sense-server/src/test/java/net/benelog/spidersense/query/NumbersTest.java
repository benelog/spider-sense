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
}
