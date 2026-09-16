package net.benelog.spidersense.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

/**
 * Query parameters, read the same way by every endpoint.
 *
 * <p>Nothing here parses a number by hand: Spider Silk's typed extraction already
 * answers 400 for a value that will not parse, and {@code error(BAD_REQUEST, ...)}
 * turns that into the {@code {"error": "..."}} body the contract promises. So a
 * malformed {@code from} is a 400 for free.
 *
 * <p>The window is the one thing that needs state: {@code since=before} is a
 * question for the {@code mark} table, so this class holds the {@link Selectors}
 * that answers it while everything else stays static. Every windowed endpoint
 * takes {@code since}/{@code until} beside {@code from}/{@code to}, which is what
 * lets an agent call the UI's own endpoints without doing arithmetic.
 */
final class Params {

    private final Selectors selectors;

    Params(Selectors selectors) {
        this.selectors = selectors;
    }

    /**
     * The window every read endpoint takes.
     *
     * <p>{@code from}/{@code to} win when both forms are given; otherwise
     * {@code since} (default {@code 15m}) and {@code until} (default now) decide.
     */
    Window window(WebRequest req) {
        return selectors.window(optionalLong(req, "from"), optionalLong(req, "to"),
                req.queryParamOrNull("since"), req.queryParamOrNull("until"), service(req));
    }

    Selectors selectors() {
        return selectors;
    }

    /**
     * Whether this request wants the Markdown rendering.
     *
     * <p>{@code format=text} says so outright; otherwise the first type of
     * {@code Accept} decides, because that is what a client states when it can only
     * read one thing. JSON stays the default, so every browser and every existing
     * caller is unaffected (api.md).
     */
    static boolean wantsText(WebRequest req) {
        String format = req.queryParamOrNull("format");
        if (format != null) {
            return "text".equalsIgnoreCase(format) || "markdown".equalsIgnoreCase(format);
        }
        String accept = req.header("Accept");
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String first = accept.split(",")[0].split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        return "text/markdown".equals(first) || "text/plain".equals(first);
    }

    /** {@code full=true} keeps statements whole and expands collapsed spans. */
    static boolean full(WebRequest req) {
        return req.queryParam("full", Boolean::parseBoolean, false);
    }

    /** One answer, rendered the way this request asked for it and no other way. */
    static WebResponse answer(WebRequest req, Reports.Report report) {
        return wantsText(req)
                ? WebResponse.text(report.text()).contentType(Text.CONTENT_TYPE)
                : WebResponse.json(report.json());
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
