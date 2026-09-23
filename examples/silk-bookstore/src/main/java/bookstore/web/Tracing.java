package bookstore.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import net.benelog.spidersilk.RequestCompletion;

/**
 * Records on this application's server spans the exception a request failed
 * with.
 *
 * <p>Spider Silk answers an exception before the servlet layer (and so the
 * agent) ever sees it, so the exception is recorded from the request logger,
 * where {@code completion.exception()} is what the handler threw and
 * {@code completion.statusCode()} is what it became. Only a 500 is recorded: a
 * 400 for a bad rating is the caller's mistake, and a 404 thrown as
 * {@code HttpException} is a status, not a failure, and never appears there at
 * all.
 *
 * <p>The span's name and {@code http.route} need nothing here: the Spider Sense
 * extension instruments Spider Silk's router and reports the route that matched,
 * {@code /books/{id}}, where the servlet instrumentation alone would report the
 * servlet mapping {@code /*} for every request.
 *
 * <p>Nothing here depends on an agent being attached. With no agent on the
 * command line, {@code Span.current()} is the OpenTelemetry API's no-op span and
 * every call on it does nothing at all.
 */
public final class Tracing {

    private Tracing() {
    }

    /** Called from the request logger: puts the exception behind a 500 on the span. */
    public static void record(RequestCompletion completion) {
        Exception exception = completion.exception();
        if (exception == null || completion.statusCode() < 500) {
            return;
        }
        Span span = Span.current();
        span.recordException(exception);
        span.setStatus(StatusCode.ERROR, exception.getMessage());
    }
}
