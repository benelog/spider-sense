package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Numbers the API writes: rounded to three decimals where a third decimal exists, never clamped. */
class CodecsTest {

    @Test
    void aDurationIsRoundedToThreeDecimals() {
        assertThat(Codecs.round(12.34567)).isEqualTo(12.346);
    }

    @Test
    void aValueTooLargeForThreeDecimalsIsWrittenAsItIs() {
        assertThat(Codecs.round(1e20)).isEqualTo(1e20);
        assertThat(Codecs.round(1.2345678901234568e16)).isEqualTo(1.2345678901234568e16);
        assertThat(Codecs.round(-1e20)).isEqualTo(-1e20);
        assertThat(Codecs.round(Double.MAX_VALUE)).isEqualTo(Double.MAX_VALUE);
    }
}
