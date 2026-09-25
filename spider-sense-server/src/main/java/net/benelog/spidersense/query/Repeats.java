package net.benelog.spidersense.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fold both N+1 rules share: the same statement or the same outbound call, five
 * or more times under one entry span (findings.adoc#n-plus-one, #n-plus-one-http).
 *
 * <p>findings.adoc says the severity, the impact, the evidence traces, the text and
 * the five {@code numbers} of {@code n-plus-one-http} are those of {@code n-plus-one};
 * they are one fold and one set of numbers here, so the two rules cannot drift apart.
 * A rule supplies only what it names: its spans, its verb, its subject, its
 * statement and where its code comes from.
 */
final class Repeats {

    /** The repeats within one entry span that make a request affected (storage.adoc#reads). */
    static final int MIN_REPEATS = 5;

    /** Repeats at which an N+1 stops being a nuisance and becomes the bug. */
    static final int LOUD_REPEATS = 20;

    private Repeats() {
    }

    /**
     * One span of a group under one entry span: one run of a statement, or one
     * outbound call.
     *
     * @param entrySpanId the entry span the parent-chain walk attributes it to
     * @param groupKey    what repeats: the query id, or the call name
     * @param located     whether it carries {@code code.stacktrace}, which the
     *        extension writes on the fifth repeat
     * @param detail      what the rule reads back about it: the statement and its
     *        attributes, or the outbound call
     */
    record Occurrence<D>(String traceId, String entrySpanId, String endpointId, String endpoint,
            String service, String groupKey, double durationMs, long startMs, boolean located,
            D detail) {
    }

    /**
     * One affected request: the occurrences of one group under one entry span.
     *
     * @param first   the first occurrence read, which names the group
     * @param located the first occurrence that carries {@code code.stacktrace}, else
     *        the first, which the code is taken from
     * @param startMs the earliest occurrence's start
     */
    record RequestRepeat<D>(Occurrence<D> first, Occurrence<D> located, int repeats,
            double totalMs, long startMs) {

        String traceId() {
            return first.traceId();
        }
    }

    /**
     * The affected requests, grouped by endpoint and group, in the order their first
     * occurrence was read.
     *
     * <p>An entry span with fewer than {@link #MIN_REPEATS} occurrences of a group is
     * not affected, and two endpoints of one trace each running a statement four
     * times are two such entry spans, not one N+1.
     */
    static <D> List<List<RequestRepeat<D>>> foldPerEntry(List<Occurrence<D>> occurrences) {
        Map<String, List<Occurrence<D>>> byEntry = new LinkedHashMap<>();
        for (Occurrence<D> occurrence : occurrences) {
            byEntry.computeIfAbsent(occurrence.entrySpanId() + "\0" + occurrence.groupKey(),
                    key -> new ArrayList<>()).add(occurrence);
        }
        Map<String, List<RequestRepeat<D>>> byEndpointAndGroup = new LinkedHashMap<>();
        for (List<Occurrence<D>> spans : byEntry.values()) {
            if (spans.size() < MIN_REPEATS) {
                continue;
            }
            Occurrence<D> first = spans.get(0);
            Occurrence<D> located = first;
            double totalMs = 0;
            long start = Long.MAX_VALUE;
            for (Occurrence<D> span : spans) {
                totalMs += span.durationMs();
                start = Math.min(start, span.startMs());
                if (!located.located() && span.located()) {
                    located = span;
                }
            }
            byEndpointAndGroup
                    .computeIfAbsent(first.endpointId() + "\0" + first.groupKey(), key -> new ArrayList<>())
                    .add(new RequestRepeat<>(first, located, spans.size(), totalMs, start));
        }
        return new ArrayList<>(byEndpointAndGroup.values());
    }

    /**
     * The numbers of one endpoint's repeated group over its affected requests.
     *
     * <p>The median is the nearest-rank one ({@code ceil(n / 2)}-th smallest), the
     * definition {@code PERCENTILE_DISC} gives every other percentile.
     */
    static final class RepeatStats<D> {

        private final List<RequestRepeat<D>> affected;
        private final int[] repeats;
        private final double msPerRequest;

        private RepeatStats(List<RequestRepeat<D>> affected, int[] repeats, double msPerRequest) {
            this.affected = affected;
            this.repeats = repeats;
            this.msPerRequest = msPerRequest;
        }

        /** @param affected at least one request, as {@link #foldPerEntry} groups them */
        static <D> RepeatStats<D> of(List<RequestRepeat<D>> affected) {
            int[] repeats = new int[affected.size()];
            double totalMs = 0;
            for (int i = 0; i < affected.size(); i++) {
                repeats[i] = affected.get(i).repeats();
                totalMs += affected.get(i).totalMs();
            }
            Arrays.sort(repeats);
            return new RepeatStats<>(List.copyOf(affected), repeats, totalMs / affected.size());
        }

        /** The first occurrence of the first affected request, which names the group. */
        Occurrence<D> first() {
            return affected.get(0).first();
        }

        long affected() {
            return affected.size();
        }

        long median() {
            return repeats[(int) Math.ceil(0.5 * repeats.length) - 1];
        }

        long max() {
            return repeats[repeats.length - 1];
        }

        double msPerRequest() {
            return msPerRequest;
        }

        /** {@code high} at {@link #LOUD_REPEATS} a request or past the slow-request threshold. */
        String severity(long slowRequestMs) {
            return median() >= LOUD_REPEATS || msPerRequest > slowRequestMs
                    ? Findings.HIGH : Findings.MEDIUM;
        }

        /** What a finding is ranked by within its kind: the repeats it costs over the window. */
        double impact() {
            return affected.size() * (double) median();
        }

        /** The affected requests, the newest first; the code lookup walks them in this order. */
        List<RequestRepeat<D>> newest() {
            List<RequestRepeat<D>> newest = new ArrayList<>(affected);
            newest.sort(Comparator.comparingLong((RequestRepeat<D> each) -> each.startMs()).reversed());
            return newest;
        }

        /** The traces of the three most recent affected requests, distinct. */
        List<String> evidenceTraces() {
            List<String> traces = new ArrayList<>();
            for (RequestRepeat<D> repeat : newest()) {
                if (traces.size() < Findings.EVIDENCE_TRACES && !traces.contains(repeat.traceId())) {
                    traces.add(repeat.traceId());
                }
            }
            return List.copyOf(traces);
        }

        /** The five {@code numbers} findings.adoc#n-plus-one lists, in its order. */
        Map<String, Object> numbers(long requests) {
            Map<String, Object> numbers = new LinkedHashMap<>();
            numbers.put("requests", requests);
            numbers.put("affected", affected());
            numbers.put("medianRepeats", median());
            numbers.put("maxRepeats", max());
            numbers.put("msPerRequest", msPerRequest);
            return numbers;
        }

        /**
         * {@code 3 of 40 requests repeated it; 42, 42 and 41 times; 12 ms per request in
         * that statement}.
         *
         * @param unit what repeats: {@code statement} or {@code call}
         */
        String why(long requests, String unit) {
            return affected() + " of " + Numbers.plural(requests, "request") + " repeated it; "
                    + spokenRepeats(repeats) + " times; " + Numbers.millis(msPerRequest)
                    + " per request in that " + unit;
        }
    }

    /** {@code 42, 42 and 41}: the three largest of sorted repeats, as a person reads them out. */
    static String spokenRepeats(int[] sorted) {
        List<String> largest = new ArrayList<>();
        for (int i = sorted.length - 1; i >= 0 && largest.size() < 3; i--) {
            largest.add(String.valueOf(sorted[i]));
        }
        if (largest.size() == 1) {
            return largest.get(0);
        }
        String last = largest.remove(largest.size() - 1);
        return String.join(", ", largest) + " and " + last;
    }
}
