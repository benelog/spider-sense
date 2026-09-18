package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules that turn a span into a tingle: a slow request, a slow query, an
 * error.
 *
 * <p>They run at ingest rather than at query time because a tingle is an event.
 * The SSE stream pushes it the moment the flush that carried it commits, and
 * recomputing "what was slow" from the window on every poll would give the UI a
 * different list each time the window moved. The rows themselves live in the
 * {@code tingle} table, so the Overview still lists them after a restart.
 */
public final class Tingles {

    private final long slowRequestMs;
    private final long slowQueryMs;
    private final IgnoredEndpoints ignored;

    /** The thresholds with the ignore list at its documented default. */
    public Tingles(long slowRequestMs, long slowQueryMs) {
        this(slowRequestMs, slowQueryMs, IgnoredEndpoints.defaults());
    }

    public Tingles(long slowRequestMs, long slowQueryMs, IgnoredEndpoints ignored) {
        this.slowRequestMs = slowRequestMs;
        this.slowQueryMs = slowQueryMs;
        this.ignored = ignored;
    }

    public long slowRequestMs() {
        return slowRequestMs;
    }

    public long slowQueryMs() {
        return slowQueryMs;
    }

    /** The endpoints {@code spidersense.ignore.endpoints} takes out of the request count. */
    public IgnoredEndpoints ignored() {
        return ignored;
    }

    /**
     * Whether the span counts as a request: an entry span whose endpoint is not
     * ignored.
     *
     * <p>This is the one place the question is answered. {@link SpanRecord#isEntry()}
     * only knows the span; the ignore list is configuration, and the {@code entry}
     * column, the {@code slow} column and every tingle have to agree on it.
     */
    public boolean isEntry(SpanRecord span) {
        return span.isEntry() && !ignored.matches(span);
    }

    /** Whether the span is what the {@code span.slow} column means. */
    public boolean isSlow(SpanRecord span) {
        double durationMs = span.durationMillis();
        return (isEntry(span) && durationMs > slowRequestMs)
                || (span.dbStatement() != null && durationMs > slowQueryMs);
    }

    /**
     * The tingles one span produces.
     *
     * <p>An error tingle is raised for an entry span that failed, and for any
     * other span that carries an exception event of its own. That keeps a failure
     * which propagated up a trace from producing one tingle per frame, while
     * still reporting the deep span that actually threw.
     */
    public List<Tingle> of(SpanRecord span) {
        List<Tingle> produced = new ArrayList<>(2);
        double durationMs = span.durationMillis();
        long at = span.startMillis();

        if (isEntry(span) && durationMs > slowRequestMs) {
            produced.add(new Tingle(Tingle.SLOW_REQUEST, at, span.service(), span.endpointName(),
                    formatMillis(durationMs), span.traceId(), span.spanId(), durationMs));
        }
        String statement = span.dbStatement();
        if (statement != null && durationMs > slowQueryMs) {
            produced.add(new Tingle(Tingle.SLOW_QUERY, at, span.service(), span.summary(),
                    statement, span.traceId(), span.spanId(), durationMs));
        }
        if (span.isError() && (isEntry(span) || span.exceptionEvent() != null)) {
            String type = span.errorType();
            String message = span.errorMessage();
            String detail = message == null || message.isEmpty() ? type : type + ": " + message;
            produced.add(new Tingle(Tingle.ERROR, at, span.service(), span.endpointName(),
                    detail, span.traceId(), span.spanId(), durationMs));
        }
        return produced;
    }

    /** {@code 1,532 ms} — the form the API examples use. */
    public static String formatMillis(double millis) {
        return String.format(Locale.US, "%,d ms", Math.round(millis));
    }
}
