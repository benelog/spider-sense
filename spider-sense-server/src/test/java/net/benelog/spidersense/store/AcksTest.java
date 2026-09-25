package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/**
 * Findings a reader has accepted: written, replaced, withdrawn, and outliving
 * the data they are about (findings.adoc#acknowledgements).
 */
class AcksTest {

    private static final String SLOW = "slow-endpoint:1a2b3c4d5e6f";
    private static final String NPLUS = "n-plus-one:0011223344ff";

    private static final long NOW = 1_700_000_000_000L;

    /** The rows are stamped with the clock, so two in one millisecond have no order. */
    private final AtomicLong clock = new AtomicLong(NOW);
    private final Store store = new Store(TestStore.memoryUrl(), null, 24, 500, 100, null,
            IgnoredEndpoints.DEFAULT, Sweeper.DEFAULT_RETENTION_SPANS, IngestCap.none(), clock::get);

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void anAcknowledgementIsWrittenAndListedNewestFirst() {
        Acks acks = store.acks();
        acks.ack(NPLUS, null);
        clock.set(NOW + 1);
        Acks.Ack newest = acks.ack(SLOW, "slow by design until the schema changes");

        List<Acks.Ack> all = acks.all(50);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).findingId()).isEqualTo(SLOW);
        assertThat(newest.at()).isEqualTo(NOW + 1);
        assertThat(all.get(0).at()).isEqualTo(NOW + 1);
        assertThat(all.get(0).note()).isEqualTo("slow by design until the schema changes");
        assertThat(all.get(1).findingId()).isEqualTo(NPLUS);
        assertThat(all.get(1).at()).isEqualTo(NOW);
        assertThat(all.get(1).note()).isNull();
    }

    @Test
    void acknowledgingAgainReplacesTheRow() {
        Acks acks = store.acks();
        acks.ack(SLOW, "first");
        acks.ack(SLOW, "second");

        assertThat(acks.all(50)).hasSize(1);
        assertThat(acks.all(50).get(0).note()).isEqualTo("second");
    }

    @Test
    void unackSaysWhetherThereWasOneToWithdraw() {
        Acks acks = store.acks();
        acks.ack(SLOW, null);

        assertThat(acks.unack(SLOW)).isTrue();
        assertThat(acks.unack(SLOW)).isFalse();
        assertThat(acks.all(50)).isEmpty();
    }

    @Test
    void theAcknowledgementsOfAPageOfFindingsComeBackById() {
        Acks acks = store.acks();
        acks.ack(SLOW, "known");

        Map<String, Acks.Ack> byId = acks.byId(Set.of(SLOW, NPLUS));

        assertThat(byId).containsOnlyKeys(SLOW);
        assertThat(byId.get(SLOW).note()).isEqualTo("known");
        assertThat(acks.byId(Set.of())).isEmpty();
    }

    @Test
    void anIdThatIsBlankOrTooLongIsRejected() {
        Acks acks = store.acks();
        assertThatThrownBy(() -> acks.ack("  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> acks.ack(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> acks.ack("x".repeat(65), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> acks.unack("")).isInstanceOf(IllegalArgumentException.class);

        assertThat(acks.ack("x".repeat(64), null).findingId()).hasSize(64);
    }

    /** A known finding stays known: the retention never takes an acknowledgement away. */
    @Test
    void aSweepLeavesAcknowledgementsAlone() {
        store.acks().ack(SLOW, "still known");

        try (Sweeper sweeper = new Sweeper(store.sql(), 0, 1)) {
            sweeper.sweep();
        }

        assertThat(store.acks().all(50)).hasSize(1);
    }

    /** The data reset does take them, because it takes everything (storage.adoc). */
    @Test
    void theDataResetEmptiesThem() {
        store.acks().ack(SLOW, null);

        store.clear();

        assertThat(store.acks().all(50)).isEmpty();
    }

    /** A resolution is the same row with the flag set, and the newer decision wins (findings.adoc#resolutions). */
    @Test
    void aResolutionReplacesAnAcknowledgementAndIsNotListedAsOne() {
        Acks acks = store.acks();
        acks.ack(SLOW, "accepted");

        Acks.Ack resolved = acks.resolve(SLOW, "added the index");

        assertThat(resolved.resolved()).isTrue();
        assertThat(acks.all(50)).as("the list is of acknowledgements").isEmpty();
        assertThat(acks.byId(Set.of(SLOW)).get(SLOW).resolved()).isTrue();
        assertThat(acks.byId(Set.of(SLOW)).get(SLOW).note()).isEqualTo("added the index");
        assertThat(acks.unack(SLOW)).as("a resolution is not an acknowledgement").isFalse();

        assertThat(acks.ack(SLOW, null).resolved()).isFalse();
        assertThat(acks.unresolve(SLOW)).as("an acknowledgement is not a resolution").isFalse();
        assertThat(acks.all(50)).hasSize(1);

        acks.resolve(SLOW, null);
        assertThat(acks.unresolve(SLOW)).isTrue();
        assertThat(acks.byId(Set.of(SLOW))).isEmpty();
    }
}
