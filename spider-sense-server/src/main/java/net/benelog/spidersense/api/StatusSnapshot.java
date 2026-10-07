package net.benelog.spidersense.api;

import java.util.List;

import net.benelog.spidersense.query.ResponseBuckets;
import net.benelog.spidersense.store.Database;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@code status} reports, read once (api.adoc#status).
 *
 * <p>{@link Codecs#status} and {@link Text#status(StatusSnapshot)} each pick their fields from these
 * values, so the two renderings of one call report the same counts even while ingest runs, and the
 * counting queries run once per call rather than once per rendering.
 *
 * @param mode        {@code agent}, {@code standalone}, or {@code file} for the CLI's read-only open
 * @param endpoint    the OTLP base URL to advertise, or null when there is no server
 * @param startedAt   when the server started, or 0 when there is none
 * @param droppedBatches batches the writer dropped, 0 with no writer
 * @param queued      batches waiting for the writer, 0 with no writer
 * @param services    how many services the store has seen
 * @param serviceNames the names of the services seen, the most recently seen first, at most
 *        {@link #SERVICE_NAMES} of them
 * @param oldestSpan  the start of the oldest span, or 0 or less when there is none
 */
record StatusSnapshot(
        String mode,
        @Nullable String endpoint,
        long startedAt,
        long now,
        @Nullable String embeddedService,
        long slowRequestMs,
        long slowQueryMs,
        ResponseBuckets responseBuckets,
        List<String> ignoredEndpoints,
        List<String> appPackages,
        List<String> frameworkPrefixes,
        @Nullable String jar,
        int retentionHours,
        long retentionSpans,
        @Nullable Long maxSpansPerSecond,
        Database.Storage storage,
        long droppedBatches,
        long droppedSpans,
        long queued,
        long spans,
        long traces,
        long logs,
        long metricSeries,
        long services,
        List<String> serviceNames,
        long oldestSpan,
        long oldestLog) {

    /** The most service names status lists (api.adoc#status); the count says how many there are. */
    static final int SERVICE_NAMES = 20;
}
