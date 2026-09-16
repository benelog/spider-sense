package bookstore.web;

import io.opentelemetry.api.trace.Span;
import net.benelog.spidersilk.App;

/**
 * Names this application's server spans after the route that handled them.
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
 * gives Spider Sense the endpoint identity it aggregates on.
 *
 * <p>{@code WebRequest} carries the path and the resolved path variables but not
 * the template they came from, so {@link RouteMatcher} recovers it from
 * {@code app.routes()} — the same table the router used.
 *
 * <p>Nothing here depends on an agent being attached. With no agent on the
 * command line, {@code Span.current()} is the OpenTelemetry API's no-op span and
 * both calls do nothing at all, so this is dead weight rather than a
 * requirement: the application runs plain with no change.
 */
public final class Tracing {

    private Tracing() {
    }

    /**
     * Installs the filter. Call it after every route is registered — the
     * matcher takes a snapshot of {@code app.routes()}.
     */
    public static void install(App app) {
        RouteMatcher matcher = new RouteMatcher(app.routes());
        app.beforeRoute(req -> {
            String template = matcher.match(req.method(), req.path());
            if (template != null) {
                Span span = Span.current();
                span.updateName(req.method() + " " + template);
                span.setAttribute("http.route", template);
            }
            return null;   // never answers; it only labels
        });
    }
}
