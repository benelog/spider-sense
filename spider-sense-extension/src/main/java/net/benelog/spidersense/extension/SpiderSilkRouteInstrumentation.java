package net.benelog.spidersense.extension;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.benelog.spidersilk.Route;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The one method this module instruments: {@code WebRequest.withRoute(Route, Map)}, which Spider
 * Silk calls once per request, on the request thread, when the router has matched a route and
 * before any handler runs.
 *
 * <p>Its argument is the route as registered, group prefixes resolved: {@code /books/{id}}, which
 * is already the path-template syntax {@code http.route} is written in. The advice hands it to
 * {@link HttpServerRoute#update}, with the controller as the source, which outranks the servlet
 * mapping the servlet instrumentation reported; the agent then renames the server span to
 * {@code GET /books/{id}} as it does for a Spring MVC handler.
 *
 * <p>A request no route matched (a static file, a 404, a 405) never reaches this method, and keeps
 * the servlet's {@code /*}.
 */
public final class SpiderSilkRouteInstrumentation implements TypeInstrumentation {

    static final String WEB_REQUEST = "net.benelog.spidersilk.WebRequest";

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(WEB_REQUEST);
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("withRoute").and(takesArguments(2)),
                WithRouteAdvice.class.getName());
    }

    /** Inlined into {@code WebRequest}, so the route type resolves in the application's loader. */
    @SuppressWarnings("unused")
    public static class WithRouteAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) Route route) {
            HttpServerRoute.update(Context.current(), HttpServerRouteSource.CONTROLLER, route.path());
        }
    }
}
