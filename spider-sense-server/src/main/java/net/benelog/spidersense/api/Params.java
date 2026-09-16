package net.benelog.spidersense.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.benelog.spidersense.query.Window;
import net.benelog.spidersilk.WebRequest;

/**
 * Query parameters, read the same way by every endpoint.
 *
 * <p>Nothing here parses a number by hand: Spider Silk's typed extraction already
 * answers 400 for a value that will not parse, and {@code error(BAD_REQUEST, ...)}
 * turns that into the {@code {"error": "..."}} body the contract promises. So the
 * window helper is three lines, and a malformed {@code from} is a 400 for free.
 */
final class Params {

    private Params() {
    }

    /** The window every read endpoint takes: {@code to} defaults to now, {@code from} to 15 minutes before. */
    static Window window(WebRequest req) {
        long to = req.queryParam("to", Long::parseLong, System.currentTimeMillis());
        long from = req.queryParam("from", Long::parseLong, to - Window.DEFAULT_RANGE_MS);
        return Window.of(from, to);
    }

    static int limit(WebRequest req, int fallback, int max) {
        int limit = req.queryParam("limit", Integer::parseInt, fallback);
        return Math.min(Math.max(1, limit), max);
    }

    static String service(WebRequest req) {
        return req.queryParamOrNull("service");
    }

    static Long optionalLong(WebRequest req, String name) {
        return req.queryParamOrNull(name) == null ? null : req.queryParam(name, Long::parseLong);
    }

    /**
     * The {@code attr.<key>=<value>} filters of {@code /api/metrics/series}.
     *
     * <p>Read from the raw query string because the servlet API offers no way to
     * ask "which parameters start with this prefix", and reaching through
     * {@code raw()} for a parameter map is the escape hatch, not the answer.
     */
    static Map<String, String> attributeFilters(WebRequest req) {
        Map<String, String> filters = new LinkedHashMap<>();
        String query = req.queryString();
        if (query == null || query.isEmpty()) {
            return filters;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = decode(pair.substring(0, equals));
            if (key.startsWith("attr.") && key.length() > "attr.".length()) {
                filters.put(key.substring("attr.".length()), decode(pair.substring(equals + 1)));
            }
        }
        return filters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
