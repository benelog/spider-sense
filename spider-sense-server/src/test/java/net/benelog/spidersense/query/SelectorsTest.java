package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.store.Store;

/** The forms of {@code since} and {@code until}, and the two ways they can fail. */
class SelectorsTest {

    private static final long NOW = 1_700_000_000_000L;

    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null);
    private final Selectors selectors = new Selectors(store.marks(), () -> NOW);

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aDurationCountsBackFromTheAnchor() {
        assertThat(selectors.resolve("30s", NOW, null)).isEqualTo(NOW - 30_000);
        assertThat(selectors.resolve("5m", NOW, null)).isEqualTo(NOW - 300_000);
        assertThat(selectors.resolve("2h", NOW, null)).isEqualTo(NOW - 7_200_000);
        assertThat(selectors.resolve("1d", NOW, null)).isEqualTo(NOW - 86_400_000);
    }

    @Test
    void epochMillisecondsAndNowAreTakenAsWritten() {
        assertThat(selectors.resolve("1758000000000", NOW, null)).isEqualTo(1_758_000_000_000L);
        assertThat(selectors.resolve("now", NOW - 60_000, null)).isEqualTo(NOW);
    }

    @Test
    void aNameResolvesToTheNewestMarkWithIt() {
        store.marks().create("before", null, null, 1_000L);
        store.marks().create("before", null, null, 2_000L);

        assertThat(selectors.resolve("before", NOW, null)).isEqualTo(2_000L);
    }

    @Test
    void startPrefersTheMarkOfTheServiceAskedAboutAndFallsBackToAny() {
        store.marks().create("start", "orders", "pid 1", 1_000L);
        store.marks().create("start", "books", "pid 2", 2_000L);

        assertThat(selectors.resolve("start", NOW, "orders")).isEqualTo(1_000L);
        assertThat(selectors.resolve("start", NOW, "nobody")).isEqualTo(2_000L);
        assertThat(selectors.resolve("start", NOW, null)).isEqualTo(2_000L);
    }

    @Test
    void anUnknownMarkAndAMalformedSelectorAreDifferentFailures() {
        assertThatThrownBy(() -> selectors.resolve("after-fix", NOW, null))
                .isInstanceOf(Selectors.UnknownMark.class)
                .hasMessageContaining("after-fix");

        assertThatThrownBy(() -> selectors.resolve("5 minutes ago", NOW, null))
                .isInstanceOf(Selectors.BadSelector.class);
        assertThatThrownBy(() -> selectors.resolve("", NOW, null))
                .isInstanceOf(Selectors.BadSelector.class);
        assertThatThrownBy(() -> selectors.resolve("99999999999999999999", NOW, null))
                .as("epoch milliseconds past Long.MAX_VALUE")
                .isInstanceOf(Selectors.BadSelector.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void theWindowDefaultsToTheLastFifteenMinutesAndFromAndToWin() {
        Window fifteen = selectors.window(null, NOW, null, null, null);
        assertThat(fifteen.from()).isEqualTo(NOW - 900_000);
        assertThat(fifteen.to()).isEqualTo(NOW);

        Window selected = selectors.window(null, null, "5m", String.valueOf(NOW), null);
        assertThat(selected.to()).isEqualTo(NOW);
        assertThat(selected.from()).isEqualTo(NOW - 300_000);

        Window explicit = selectors.window(NOW - 1000, NOW, "5m", "now", null);
        assertThat(explicit.from()).isEqualTo(NOW - 1000);
        assertThat(explicit.to()).isEqualTo(NOW);
    }

    @Test
    void theWindowReadsNowOnceForBothEnds() {
        Window defaulted = selectors.window(null, null, null, null, null);
        assertThat(defaulted.from()).isEqualTo(NOW - 900_000);
        assertThat(defaulted.to()).isEqualTo(NOW);

        Window untilNow = selectors.window(null, null, "15m", "now", null);
        assertThat(untilNow.from()).isEqualTo(NOW - 900_000);
        assertThat(untilNow.to()).isEqualTo(NOW);

        Window sinceNow = selectors.window(null, null, "now", null, null);
        assertThat(sinceNow.from()).isEqualTo(NOW);
        assertThat(sinceNow.to()).isEqualTo(NOW);
    }

    @Test
    void theWallClockIsWhatNowIsInProduction() {
        long before = System.currentTimeMillis();
        long now = new Selectors(store.marks()).resolve("now", 0, null);

        assertThat(now).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void aSinceAfterTheUntilIsTheCallersMistake() {
        assertThatThrownBy(() -> selectors.window(null, null, "now", "5m", null))
                .isInstanceOf(Selectors.BadSelector.class)
                .hasMessage("since resolves to " + NOW + ", which is after until " + (NOW - 300_000));
        assertThatThrownBy(() -> selectors.window(null, null,
                String.valueOf(NOW), String.valueOf(NOW - 1000), null))
                .isInstanceOf(Selectors.BadSelector.class);
    }
}
