package bookstore.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.RequestCompletion;
import net.benelog.spidersilk.Route;

/**
 * Names this application's server spans after the route that handled them, and
 * records on them the exception a request failed with.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The OpenTelemetry Java agent instruments Jetty and the Servlet API, which
 * is the layer it can see. Spider Silk's router lives above that layer: one
 * {@code AppServlet} is mapped at {@code /*} and does the routing itself, so
 * every request looks the same from where the agent stands. Left alone, the
 * agent names every server span {@code GET /*} and sets no {@code http.route},
 * and an APM that groups by endpoint then has exactly one endpoint to show —
 * which makes the whole point of the dashboard disappear.
 *
 * <p>The fix is for the application to say what the agent cannot know. A
 * {@code beforeRoute} filter runs after the router picked a route and inside
 * the agent's server span, so {@link Span#current()} there is that span:
 * renaming it to {@code METHOD /route/{template}} and setting {@code http.route}
 * gives Spider Sense the endpoint identity it aggregates on. The template is
 * {@code req.route().path()}, the entry of {@code app.routes()} the router
 * chose, so the name on the span is the route that actually ran.
 *
 * <p>The same goes for failures. Spider Silk answers an exception before the
 * servlet layer (and so the agent) ever sees it, so the exception is recorded
 * from the request logger, where {@code completion.exception()} is what the
 * handler threw and {@code completion.statusCode()} is what it became. Only a
 * 500 is recorded: a 400 for a bad rating is the caller's mistake, and a 404
 * thrown as {@code HttpException} is a status, not a failure, and never
 * appears there at all.
 *
 * <p>Nothing here depends on an agent being attached. With no agent on the
 * command line, {@code Span.current()} is the OpenTelemetry API's no-op span and
 * every call on it does nothing at all, so this is dead weight rather than a
 * requirement: the application runs plain with no change.
 */
public final class Tracing {

    private Tracing() {
    }

    /** Installs the filter that names the span. Order does not matter: it reads the route off each request. */
    public static void install(App app) {
        app.beforeRoute(req -> {
            Route route = req.route();
            if (route != null) {
                Span span = Span.current();
                span.updateName(req.method() + " " + route.path());
                span.setAttribute("http.route", route.path());
            }
            return null;   // never answers; it only labels
        });
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
