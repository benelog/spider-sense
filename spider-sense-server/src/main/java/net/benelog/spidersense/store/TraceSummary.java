package net.benelog.spidersense.store;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A {@code trace} row as its spans make it: the root, the extent, the counts and
 * whether the trace was slow or failed (storage.adoc#schema).
 *
 * <p>A trace arrives in several exports and from several services, so the row is
 * only ever correct as a re-aggregation of every span stored for it; this is that
 * aggregation, apart from the database that {@link TraceSummaries} reads and
 * writes it with.
 *
 * @param services the names of the services, as the JSON array the column holds,
 *                 leaving out the names past its width
 */
record TraceSummary(String traceId, long startMs, long endMs, long durationNs, String rootSpanId,
        String rootName, String rootService, String rootKind, String services, int spanCount,
        int errorCount, int dbCount, @Nullable Long httpStatus, boolean slow, boolean error) {

    /** One span row of a trace, the columns its summary is rebuilt from. */
    record Span(String traceId, String spanId, @Nullable String parentSpanId, String service, String name,
            @Nullable String endpoint, String kind, long startMs, long startNs, long durationNs,
            boolean error, boolean isDb, @Nullable Long httpStatus) {
    }

    /**
     * The summary of one trace's spans.
     *
     * <p>The root is the earliest span whose parent is not among them: the
     * parentless span, or the first span of a service whose caller's spans have
     * not arrived. A trace in which every span names another as its parent has no
     * such span, and its first span stands in. The extent is taken in nanoseconds
     * for the duration and in milliseconds for the start and end columns, each
     * from the span's own start of that unit. A trace is slow when it took longer
     * than {@code slowRequestMs}, as a request is.
     *
     * @param spans every span stored for the trace, at least one
     * @throws IllegalArgumentException when there is no span
     */
    static TraceSummary of(List<Span> spans, long slowRequestMs) {
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("a trace summary needs at least one span");
        }
        Set<String> spanIds = new HashSet<>();
        for (Span span : spans) {
            spanIds.add(span.spanId());
        }
        long startMs = Long.MAX_VALUE;
        long endMs = Long.MIN_VALUE;
        long startNs = Long.MAX_VALUE;
        long endNs = Long.MIN_VALUE;
        int errorCount = 0;
        int dbCount = 0;
        Set<String> services = new LinkedHashSet<>();
        Span root = null;
        for (Span span : spans) {
            startMs = Math.min(startMs, span.startMs());
            endMs = Math.max(endMs, span.startMs() + span.durationNs() / 1_000_000L);
            startNs = Math.min(startNs, span.startNs());
            endNs = Math.max(endNs, span.startNs() + span.durationNs());
            services.add(span.service());
            if (span.error()) {
                errorCount++;
            }
            if (span.isDb()) {
                dbCount++;
            }
            boolean isRoot = span.parentSpanId() == null || !spanIds.contains(span.parentSpanId());
            if (isRoot && (root == null || span.startNs() < root.startNs())) {
                root = span;
            }
        }
        if (root == null) {
            root = spans.get(0);
        }
        long durationNs = Math.max(0, endNs - startNs);
        double durationMs = durationNs / 1_000_000.0;
        String rootName = root.endpoint() != null ? root.endpoint() : root.name();
        if (rootName.length() > Columns.TRACE_ROOT_NAME) {
            rootName = rootName.substring(0, Columns.TRACE_ROOT_NAME);
        }
        return new TraceSummary(root.traceId(), startMs, endMs, durationNs, root.spanId(), rootName,
                root.service(), root.kind(), AttrJson.encodeStrings(List.copyOf(services), Columns.TRACE_SERVICES),
                spans.size(), errorCount, dbCount, root.httpStatus(),
                Tingles.isSlowRequest(durationMs, slowRequestMs), errorCount > 0);
    }
}
