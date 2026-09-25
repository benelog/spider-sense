package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;

/** The pacing of {@code /api/events} and the arithmetic of its stats frame, with no stream. */
class EventsApiTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    void noStatsFrameWhileNothingArrives() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);

        assertThat(pacer.statsDue(T0 + 5_000, 0)).isFalse();
    }

    @Test
    void statsAtMostOnceASecondAndOnlyForWhatArrivedSince() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);
        assertThat(pacer.statsDue(T0 + 10, T0 + 5)).as("the first ingest is sent at once").isTrue();

        pacer = pacer.statsSent(T0 + 10);
        assertThat(pacer.statsDue(T0 + 500, T0 + 400)).as("within the second").isFalse();
        assertThat(pacer.statsDue(T0 + 1_010, T0 + 400)).isTrue();
        assertThat(pacer.statsDue(T0 + 1_010, T0 + 10)).as("nothing new since the last frame").isFalse();
        assertThat(pacer.secondsSinceStats(T0 + 2_010)).isEqualTo(2.0);
    }

    @Test
    void aKeepaliveAfterFifteenQuietSecondsAndAStatsFrameCountsAsSpeech() {
        EventsApi.Pacer pacer = EventsApi.Pacer.start(T0);
        assertThat(pacer.keepaliveDue(T0 + 14_999)).isFalse();
        assertThat(pacer.keepaliveDue(T0 + 15_000)).isTrue();

        pacer = pacer.statsSent(T0 + 10_000);
        assertThat(pacer.keepaliveDue(T0 + 15_000)).isFalse();
        assertThat(pacer.keepaliveSent(T0 + 25_000).keepaliveDue(T0 + 30_000)).isFalse();
    }

    @Test
    void theRatesAreWhatArrivedPerSecondAndNeverNegative() {
        EventsApi.Counts before = new EventsApi.Counts(100, 10, 50, 0);
        EventsApi.Counts after = new EventsApi.Counts(300, 20, 60, 2);

        Json.JsonObject stats = EventsApi.stats(after, before, 2.0, T0);
        assertThat(stats.getLong("at")).isEqualTo(T0);
        assertThat(stats.getLong("spans")).isEqualTo(300);
        assertThat(stats.getLong("droppedSpans")).isEqualTo(2);
        assertThat(stats.getObject("perSecond").getDouble("spans")).isEqualTo(100.0);
        assertThat(stats.getObject("perSecond").getDouble("logs")).isEqualTo(5.0);

        Json.JsonObject cleared = EventsApi.stats(new EventsApi.Counts(0, 0, 0, 0), after, 1.0, T0);
        assertThat(cleared.getObject("perSecond").getDouble("spans")).as("a clear in between").isZero();
    }
}
