package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.Repeats.Occurrence;
import net.benelog.spidersense.query.Repeats.RepeatStats;
import net.benelog.spidersense.query.Repeats.RequestRepeat;

/** The fold both N+1 rules share, over hand-built occurrences: no store, no ingest. */
class RepeatsTest {

    /** {@code times} occurrences of one group under one entry span, one millisecond each. */
    private static List<Occurrence<String>> request(String traceId, String entry, String endpoint,
            String group, int times, long startMs) {
        List<Occurrence<String>> spans = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            spans.add(new Occurrence<>(traceId, entry, endpoint + "-id", endpoint, "svc", group,
                    1.0, startMs + i, false, "span-" + i));
        }
        return spans;
    }

    private static RepeatStats<String> statsOf(int... repeats) {
        List<Occurrence<String>> occurrences = new ArrayList<>();
        for (int i = 0; i < repeats.length; i++) {
            occurrences.addAll(request("trace-" + i, "entry-" + i, "GET /orders", "q1", repeats[i],
                    1_000L * i));
        }
        List<List<RequestRepeat<String>>> groups = Repeats.foldPerEntry(occurrences);
        assertThat(groups).hasSize(1);
        return RepeatStats.of(groups.get(0));
    }

    @Test
    void anEntrySpanWithFewerThanFiveRepeatsIsNotAffected() {
        List<Occurrence<String>> occurrences = new ArrayList<>(request("t1", "e1", "GET /a", "q1", 4, 0));
        occurrences.addAll(request("t2", "e2", "GET /a", "q1", 5, 100));

        List<List<RequestRepeat<String>>> groups = Repeats.foldPerEntry(occurrences);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(RequestRepeat::traceId).containsExactly("t2");
        assertThat(groups.get(0).get(0).repeats()).isEqualTo(5);
        assertThat(groups.get(0).get(0).totalMs()).isEqualTo(5.0);
        assertThat(groups.get(0).get(0).startMs()).isEqualTo(100);
    }

    @Test
    void twoEntrySpansOfOneTraceEachRepeatingFourTimesAreNoNPlusOne() {
        List<Occurrence<String>> occurrences = new ArrayList<>(request("t1", "e1", "GET /a", "q1", 4, 0));
        occurrences.addAll(request("t1", "e2", "GET /b", "q1", 4, 10));

        assertThat(Repeats.foldPerEntry(occurrences)).isEmpty();
    }

    @Test
    void theAffectedRequestsAreGroupedByEndpointAndGroupInReadOrder() {
        List<Occurrence<String>> occurrences = new ArrayList<>(request("t1", "e1", "GET /b", "q1", 5, 0));
        occurrences.addAll(request("t2", "e2", "GET /a", "q1", 6, 10));
        occurrences.addAll(request("t3", "e3", "GET /b", "q1", 7, 20));
        occurrences.addAll(request("t3", "e3", "GET /b", "q2", 5, 30));

        List<List<RequestRepeat<String>>> groups = Repeats.foldPerEntry(occurrences);

        assertThat(groups).extracting(group -> group.get(0).first().endpoint() + " "
                + group.get(0).first().groupKey())
                .containsExactly("GET /b q1", "GET /a q1", "GET /b q2");
        assertThat(groups.get(0)).extracting(RequestRepeat::repeats).containsExactly(5, 7);
    }

    @Test
    void theCodeComesFromTheFirstLocatedOccurrenceElseTheFirst() {
        List<Occurrence<String>> occurrences = new ArrayList<>(request("t1", "e1", "GET /a", "q1", 6, 0));
        Occurrence<String> fifth = occurrences.get(4);
        occurrences.set(4, new Occurrence<>(fifth.traceId(), fifth.entrySpanId(), fifth.endpointId(),
                fifth.endpoint(), fifth.service(), fifth.groupKey(), fifth.durationMs(), fifth.startMs(),
                true, "located"));
        occurrences.addAll(request("t2", "e2", "GET /a", "q1", 5, 100));

        List<RequestRepeat<String>> affected = Repeats.foldPerEntry(occurrences).get(0);

        assertThat(affected.get(0).located().detail()).isEqualTo("located");
        assertThat(affected.get(1).located().detail()).isEqualTo("span-0");
    }

    @Test
    void theMedianIsNearestRankOverAnEvenNumberOfRequests() {
        RepeatStats<String> stats = statsOf(8, 5, 20, 6);

        // Sorted 5, 6, 8, 20: ceil(4 / 2) = the second.
        assertThat(stats.median()).isEqualTo(6);
        assertThat(stats.max()).isEqualTo(20);
        assertThat(stats.affected()).isEqualTo(4);
        assertThat(stats.impact()).isEqualTo(24.0);
    }

    @Test
    void theMedianIsTheMiddleOverAnOddNumberOfRequests() {
        RepeatStats<String> stats = statsOf(9, 5, 7, 30, 6);

        // Sorted 5, 6, 7, 9, 30: ceil(5 / 2) = the third.
        assertThat(stats.median()).isEqualTo(7);
        assertThat(stats.max()).isEqualTo(30);
    }

    @Test
    void msPerRequestIsTheTimeInTheGroupOverTheAffectedRequests() {
        RepeatStats<String> stats = statsOf(5, 7);

        assertThat(stats.msPerRequest()).isEqualTo(6.0);
        assertThat(stats.numbers(40)).containsExactly(
                entry("requests", 40L),
                entry("affected", 2L),
                entry("medianRepeats", 5L),
                entry("maxRepeats", 7L),
                entry("msPerRequest", 6.0));
    }

    @Test
    void theSeverityIsHighFromTwentyRepeatsOrPastTheSlowRequestThreshold() {
        assertThat(statsOf(19).severity(1_000)).isEqualTo(Findings.MEDIUM);
        assertThat(statsOf(20).severity(1_000)).isEqualTo(Findings.HIGH);
        // 19 repeats of a millisecond each: 19 ms a request, past a threshold of 18 but not of 19.
        assertThat(statsOf(19).severity(19)).isEqualTo(Findings.MEDIUM);
        assertThat(statsOf(19).severity(18)).isEqualTo(Findings.HIGH);
    }

    @Test
    void theEvidenceIsTheThreeNewestDistinctTraces() {
        List<Occurrence<String>> occurrences = new ArrayList<>();
        occurrences.addAll(request("t1", "e1", "GET /a", "q1", 5, 0));
        occurrences.addAll(request("t2", "e2", "GET /a", "q1", 5, 100));
        occurrences.addAll(request("t2", "e3", "GET /a", "q1", 5, 200));
        occurrences.addAll(request("t3", "e4", "GET /a", "q1", 5, 300));
        occurrences.addAll(request("t4", "e5", "GET /a", "q1", 5, 400));

        RepeatStats<String> stats = RepeatStats.of(Repeats.foldPerEntry(occurrences).get(0));

        assertThat(stats.evidenceTraces()).containsExactly("t4", "t3", "t2");
        assertThat(stats.newest()).extracting(RequestRepeat::startMs)
                .containsExactly(400L, 300L, 200L, 100L, 0L);
    }

    @Test
    void theWhyReadsOutTheLargestThreeRepeats() {
        assertThat(statsOf(5, 42, 41, 42).why(40, "statement"))
                .isEqualTo("4 of 40 requests repeated it; 42, 42 and 41 times;"
                        + " " + Numbers.millis(32.5) + " per request in that statement");
        assertThat(Repeats.spokenRepeats(new int[]{7})).isEqualTo("7");
        assertThat(Repeats.spokenRepeats(new int[]{5, 7})).isEqualTo("7 and 5");
    }
}
