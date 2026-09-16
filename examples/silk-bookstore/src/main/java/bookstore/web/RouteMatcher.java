package bookstore.web;

import java.util.List;

import net.benelog.spidersilk.Route;

/**
 * Finds which registered route template a request path matched.
 *
 * <p>Spider Silk's {@code WebRequest} exposes the path and the resolved path
 * variables, but not the pattern they came from, and {@link Tracing} needs the
 * pattern. So this walks {@code app.routes()} — the same list the router walks —
 * with the same rules: segment by segment, {@code {name}} matches one segment,
 * a trailing {@code {name*}} or {@code *} matches the rest, and a literal
 * segment beats a variable one when both fit.
 */
public final class RouteMatcher {

    private final List<Route> routes;

    public RouteMatcher(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    /** The matching route's path template, or null when no route matches. */
    public String match(String method, String path) {
        String[] segments = split(path);
        String best = null;
        int bestScore = -1;
        for (Route route : routes) {
            if (!route.method().equalsIgnoreCase(method)) {
                continue;
            }
            int score = score(split(route.path()), segments);
            if (score > bestScore) {
                bestScore = score;
                best = route.path();
            }
        }
        return best;
    }

    /** -1 for no match; otherwise 2 per literal segment and 1 per variable, so literals win. */
    private static int score(String[] pattern, String[] path) {
        int score = 0;
        for (int i = 0; i < pattern.length; i++) {
            String segment = pattern[i];
            if (segment.equals("*") || segment.endsWith("*}")) {
                return score;                       // the tail matches the rest, including nothing
            }
            if (i >= path.length) {
                return -1;
            }
            if (segment.startsWith("{") && segment.endsWith("}")) {
                score += 1;
            } else if (segment.equals(path[i])) {
                score += 2;
            } else {
                return -1;
            }
        }
        return pattern.length == path.length ? score : -1;
    }

    private static String[] split(String path) {
        int from = 0;
        int to = path.length();
        while (from < to && path.charAt(from) == '/') {
            from++;
        }
        while (to > from && path.charAt(to - 1) == '/') {
            to--;
        }
        return from == to ? new String[0] : path.substring(from, to).split("/");
    }
}
