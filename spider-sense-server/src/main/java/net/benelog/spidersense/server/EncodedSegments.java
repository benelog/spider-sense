package net.benelog.spidersense.server;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a {@code %2F} inside one segment of an API path, so a service name or a finding id that
 * holds a {@code /} is one path parameter (api.adoc#conventions).
 *
 * <p>Spider Silk routes on the servlet path, which the container has already decoded, so
 * {@code /api/services/a%2Fb} reached the router as the three segments {@code services},
 * {@code a} and {@code b} and matched no route. In front of the servlet, this filter hands an
 * {@code /api/} request a path built from the raw request URI instead: each segment decoded on
 * its own, then a {@code /} in it written back as {@code %2F} and a {@code %} as {@code %25}, so
 * the segments are the ones the client sent and a literal segment such as {@code services} reads
 * as it always did. A handler reads a parameter through {@link #decode}, which undoes those two
 * escapes and nothing else.
 *
 * <p>Only {@code /api/} paths are rewritten: the static files and the single page are served
 * from the container's own decoded path, as before.
 */
public final class EncodedSegments extends HttpFilter {

    private static final long serialVersionUID = 1L;

    private static final String API = "/api/";

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = apiPath(request);
        chain.doFilter(path == null ? request : new Rewritten(request, path), response);
    }

    /** A segment of a path this filter wrote, as the client meant it. */
    public static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        StringBuilder decoded = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && segment.startsWith("2F", i + 1)) {
                decoded.append('/');
                i += 2;
            } else if (c == '%' && segment.startsWith("25", i + 1)) {
                decoded.append('%');
                i += 2;
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }

    /** One decoded segment with the two characters that would change its meaning escaped. */
    static String escape(String decoded) {
        return decoded.replace("%", "%25").replace("/", "%2F");
    }

    /** The path an {@code /api/} request routes on, or null to leave the request alone. */
    static @Nullable String apiPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String decoded = pathInfo == null ? servletPath : servletPath + pathInfo;
        if (!decoded.startsWith(API)) {
            return null;
        }
        String raw = request.getRequestURI();
        String context = request.getContextPath();
        if (raw != null && context != null && raw.startsWith(context)) {
            raw = raw.substring(context.length());
        }
        List<String> segments = new ArrayList<>();
        boolean rawIsUsable = raw != null && raw.startsWith(API) && raw.indexOf(';') < 0;
        if (rawIsUsable) {
            for (String segment : raw.split("/", -1)) {
                String one = percentDecode(segment);
                if (one == null || ".".equals(one) || "..".equals(one)) {
                    // The container normalised this path; route on what it made of it.
                    rawIsUsable = false;
                    break;
                }
                segments.add(escape(one));
            }
        }
        if (!rawIsUsable) {
            segments.clear();
            for (String segment : decoded.split("/", -1)) {
                segments.add(escape(segment));
            }
        }
        return String.join("/", segments);
    }

    /** A raw segment percent-decoded as UTF-8, or null when it does not decode. */
    private static @Nullable String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            // URLDecoder is a form decoder: a '+' would become a space, which in a path it is not.
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The request, routed on {@code path}: the whole of it is the path info under {@code /*}. */
    private static final class Rewritten extends HttpServletRequestWrapper {

        private final String path;

        Rewritten(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public String getPathInfo() {
            return path;
        }
    }
}
