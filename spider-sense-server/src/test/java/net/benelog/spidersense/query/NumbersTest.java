package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /** A rate's limit reads as the percentage it was given, whatever {@code limit * 100} is in binary. */
    @Test
    void aFractionReadsAsThePercentageItWasGiven() {
        assertThat(Numbers.exactlyPercent(0.07, 1)).isEqualTo("7.0");
        assertThat(Numbers.exactlyPercent(0.29, 1)).isEqualTo("29.0");
        assertThat(Numbers.exactlyPercent(0.0005, 1)).isEqualTo("0.05");
        assertThat(Numbers.exactlyPercent(0, 1)).isEqualTo("0.0");
        assertThat(Numbers.exactlyPercent(12.5, 1)).isEqualTo("1,250.0");
        assertThat(Numbers.exactlyPercent(Double.NaN, 1)).isEqualTo("—");
        for (int basisPoints = 1; basisPoints < 10_000; basisPoints++) {
            String percent = new BigDecimal(basisPoints).movePointLeft(2)
                    .setScale(basisPoints % 10 == 0 ? 1 : 2, RoundingMode.UNNECESSARY).toPlainString();
            assertThat(Numbers.exactlyPercent(basisPoints / 10_000.0, 1)).as("%d basis points", basisPoints)
                    .isEqualTo(percent);
        }
    }
}
