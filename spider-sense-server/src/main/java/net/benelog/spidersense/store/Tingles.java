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

    public Tingles(long slowRequestMs, long slowQueryMs) {
        this.slowRequestMs = slowRequestMs;
        this.slowQueryMs = slowQueryMs;
    }

    public long slowRequestMs() {
        return slowRequestMs;
    }

    public long slowQueryMs() {
        return slowQueryMs;
    }

    /** Whether the span is what the {@code span.slow} column means. */
    public boolean isSlow(SpanRecord span) {
        double durationMs = span.durationMillis();
        return (span.isEntry() && durationMs > slowRequestMs)
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

        if (span.isEntry() && durationMs > slowRequestMs) {
            produced.add(new Tingle(Tingle.SLOW_REQUEST, at, span.service(), span.endpointName(),
                    formatMillis(durationMs), span.traceId(), span.spanId(), durationMs));
        }
        String statement = span.dbStatement();
        if (statement != null && durationMs > slowQueryMs) {
            produced.add(new Tingle(Tingle.SLOW_QUERY, at, span.service(), span.summary(),
                    statement, span.traceId(), span.spanId(), durationMs));
        }
        if (span.isError() && (span.isEntry() || span.exceptionEvent() != null)) {
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
