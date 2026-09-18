package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The endpoints {@code spidersense.ignore.endpoints} takes out of the request
 * count: a list of glob patterns, compiled once.
 *
 * <p>A health check polled every few seconds is the most frequent request of a
 * typical application and the least interesting one — it is fast, it never
 * fails, and it dilutes the request count, the Apdex, {@code check} and every
 * slow-endpoint judgement. A span that matches is still stored and still renders
 * in its trace; it is simply written with {@code entry} false, exactly as a root
 * {@code INTERNAL} span is (design.md, "Ignored endpoints").
 *
 * <p>{@code **} matches anything including {@code /}, {@code *} matches anything
 * but {@code /}, {@code ?} matches one character that is not {@code /}; the match
 * is case-sensitive and covers the whole name.
 */
public final class IgnoredEndpoints {

    /** What the documented default of {@code spidersense.ignore.endpoints} is. */
    public static final String DEFAULT = "/actuator/**,/health,/healthz,/livez,/readyz";

    /** The {@code METHOD } an endpoint name starts with when the span had a route. */
    private static final Pattern METHOD_PREFIX = Pattern.compile("^[A-Z]+ ");

    private final List<String> patterns;
    private final List<Pattern> compiled;
    /** Parallel to {@link #compiled}: a pattern that starts with {@code /} names no method. */
    private final boolean[] pathOnly;

    private IgnoredEndpoints(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
        this.compiled = new ArrayList<>(this.patterns.size());
        this.pathOnly = new boolean[this.patterns.size()];
        for (int i = 0; i < this.patterns.size(); i++) {
            String glob = this.patterns.get(i);
            this.compiled.add(compile(glob));
            this.pathOnly[i] = glob.startsWith("/");
        }
    }

    /** The list at its documented default. */
    public static IgnoredEndpoints defaults() {
        return of(DEFAULT);
    }

    /**
     * The comma-separated list as the configuration spells it. Entries are
     * trimmed and blanks dropped, so an empty value — and a value of nothing but
     * commas — ignores nothing.
     */
    public static IgnoredEndpoints of(String commaSeparated) {
        List<String> patterns = new ArrayList<>();
        if (commaSeparated != null) {
            for (String entry : commaSeparated.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(trimmed);
                }
            }
        }
        return new IgnoredEndpoints(patterns);
    }

    /** The patterns as configured, for {@code /api/status}. */
    public List<String> patterns() {
        return patterns;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /**
     * Whether this span's endpoint is one of the ignored ones.
     *
     * <p>The endpoint name is tried first ({@code GET /actuator/health}, or the
     * span name when there is no route). When nothing matched and the span
     * carries {@code url.path}, the same patterns are tried against
     * {@code METHOD url.path} and {@code url.path}, so a framework that reports
     * no route is still covered.
     */
    public boolean matches(SpanRecord span) {
        if (patterns.isEmpty()) {
            return false;
        }
        if (matchesName(span.endpointName())) {
            return true;
        }
        String path = span.urlPath();
        if (path == null) {
            return false;
        }
        String method = span.httpMethod();
        return (method != null && matchesName(method + " " + path)) || matchesName(path);
    }

    /**
     * A pattern that starts with {@code /} is matched against the name with its
     * leading {@code METHOD } removed as well, so {@code /actuator/**} covers
     * every method while {@code GET /actuator/**} covers only {@code GET}.
     */
    private boolean matchesName(String name) {
        if (name == null) {
            return false;
        }
        String withoutMethod = null;
        for (int i = 0; i < compiled.size(); i++) {
            Pattern pattern = compiled.get(i);
            if (pattern.matcher(name).matches()) {
                return true;
            }
            if (pathOnly[i]) {
                if (withoutMethod == null) {
                    withoutMethod = METHOD_PREFIX.matcher(name).replaceFirst("");
                }
                if (!withoutMethod.equals(name) && pattern.matcher(withoutMethod).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The glob as a regular expression; everything that is not a wildcard is quoted. */
    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '*' && c != '?') {
                literal.append(c);
                continue;
            }
            if (!literal.isEmpty()) {
                regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
            if (c == '?') {
                regex.append("[^/]");
            } else if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else {
                regex.append("[^/]*");
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString());
    }
}
