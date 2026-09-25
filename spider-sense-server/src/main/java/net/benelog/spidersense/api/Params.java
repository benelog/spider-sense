package net.benelog.spidersense.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.benelog.spidersense.ingest.ErrorBody;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>Public only so the server's assembly can build the one instance every API
 * reads its windows through; what it reads stays inside this package.
 */
public final class Params {

    private final Selectors selectors;

    public Params(Selectors selectors) {
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
     * caller is unaffected (api.adoc).
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
        String first = accept.split(",", -1)[0].split(";", -1)[0].trim()
                .toLowerCase(java.util.Locale.ROOT);
        return "text/markdown".equals(first) || "text/plain".equals(first);
    }

    /** {@code full=true} keeps statements whole and expands collapsed spans. */
    static boolean full(WebRequest req) {
        return flag(req, "full");
    }

    /** A boolean parameter, false unless it says {@code true}. */
    static boolean flag(WebRequest req, String name) {
        return req.queryParam(name, Boolean::parseBoolean, false);
    }

    /** The value, or the {@code 404} that says what was not found. */
    static <T> T found(@Nullable T value, String missing) {
        if (value == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, missing);
        }
        return value;
    }

    /** One answer, rendered the way this request asked for it and no other way. */
    static WebResponse answer(WebRequest req, Reports.Report report) {
        return wantsText(req)
                ? WebResponse.text(report.text()).contentType(Text.CONTENT_TYPE)
                : WebResponse.json(report.json());
    }

    /**
     * A {@code 400} in the format the request asked for.
     *
     * <p>{@code /api/sql} is the one endpoint whose errors an agent reads as part
     * of the answer — a rejected statement is a message it has to act on — so a
     * caller that asked for Markdown gets the message on one line rather than a
     * JSON object it was not expecting (cli.adoc#sql).
     */
    static WebResponse badRequest(WebRequest req, @Nullable String message) {
        return wantsText(req)
                ? WebResponse.text(ErrorBody.message(message, "Bad request") + "\n")
                        .contentType(Text.CONTENT_TYPE).status(HttpStatus.BAD_REQUEST)
                : ErrorBody.response(HttpStatus.BAD_REQUEST, message, "Bad request");
    }

    /** The {@code limit} parameter, clamped by {@link Limits#clamp}. */
    static int limit(WebRequest req, int fallback, int max) {
        return Limits.clamp(req.queryParam("limit", Integer::parseInt, fallback), fallback, max);
    }

    static @Nullable String service(WebRequest req) {
        return req.queryParamOrNull("service");
    }

    static @Nullable Long optionalLong(WebRequest req, String name) {
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
        for (String pair : query.split("&", -1)) {
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
