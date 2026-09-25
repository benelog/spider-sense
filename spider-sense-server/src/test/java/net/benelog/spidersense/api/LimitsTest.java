package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void nothingAskedIsTheFallback() {
        assertThat(Limits.clamp(null, Limits.FINDINGS, Limits.FINDINGS_MAX)).isEqualTo(20);
    }

    @Test
    void anAskedLimitIsKeptBetweenOneAndTheMaximum() {
        assertThat(Limits.clamp(7, 20, 100)).isEqualTo(7);
        assertThat(Limits.clamp(0, 20, 100)).isEqualTo(1);
        assertThat(Limits.clamp(-5, 20, 100)).isEqualTo(1);
        assertThat(Limits.clamp(1_000, 20, 100)).isEqualTo(100);
    }

    @Test
    void theCliListsFewerTracesThanTheHandlerButCapsThemAlike() {
        assertThat(Limits.CLI_TRACES).isLessThan(Limits.TRACES).isEqualTo(20);
    }
}
