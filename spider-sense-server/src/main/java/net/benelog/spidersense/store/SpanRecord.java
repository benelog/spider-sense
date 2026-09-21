package net.benelog.spidersense.store;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One span as the store keeps it: the OTLP fields that matter, plus derived
 * accessors that answer the questions the API asks.
 *
 * <p>The attribute map is kept exactly as it arrived, because the span detail
 * drawer shows what the instrumentation actually sent. Normalising the two
 * generations of semantic conventions therefore happens in the accessors rather
 * than in the map: {@link #dbStatement()} answers whichever of
 * {@code db.query.text} and {@code db.statement} is present, and the drawer
 * still shows the one the agent emitted.
 *
 * @param traceId       32 lowercase hex characters
 * @param spanId        16 lowercase hex characters
 * @param parentSpanId  16 lowercase hex characters, or null for a root span
 * @param kind          {@code INTERNAL}, {@code SERVER}, {@code CLIENT}, {@code PRODUCER} or {@code CONSUMER}
 * @param status        {@code UNSET}, {@code OK} or {@code ERROR}
 * @param attributes    values are String, Long, Double, Boolean or a List of those
 */
public record SpanRecord(
        String traceId,
        String spanId,
        @Nullable String parentSpanId,
        String service,
        String name,
        String kind,
        long startNanos,
        long endNanos,
        String status,
        @Nullable String statusMessage,
        Map<String, Object> attributes,
        List<SpanEvent> events,
        String scope) {

    /** The longest statement the API ever reports; a generated SQL blob is not worth the wire. */
    public static final int MAX_STATEMENT = 2000;

    /** One event on a span; the interesting one is {@code exception}. */
    public record SpanEvent(String name, long timeNanos, Map<String, Object> attributes) {
    }

    public long startMillis() {
        return startNanos / 1_000_000L;
    }

    public long endMillis() {
        return endNanos / 1_000_000L;
    }

    public long durationNanos() {
        return Math.max(0, endNanos - startNanos);
    }

    public double durationMillis() {
        return durationNanos() / 1_000_000.0;
    }

    // --- attribute lookup across both generations of semantic conventions ---

    /** The first of {@code keys} the span carries, as a string, or null. */
    public @Nullable String attr(String... keys) {
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private @Nullable Long attrLong(String... keys) {
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value instanceof Number n) {
                return n.longValue();
            }
            if (value instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    // not a number after all; try the next spelling
                }
            }
        }
        return null;
    }

    public @Nullable String httpMethod() {
        return attr("http.request.method", "http.method");
    }

    public @Nullable String httpRoute() {
        return attr("http.route");
    }

    public @Nullable Long httpStatus() {
        return attrLong("http.response.status_code", "http.status_code");
    }

    public @Nullable String urlPath() {
        return attr("url.path", "http.target");
    }

    public @Nullable String serverAddress() {
        return attr("server.address", "net.peer.name");
    }

    public @Nullable Long serverPort() {
        return attrLong("server.port", "net.peer.port");
    }

    public @Nullable String dbSystem() {
        return attr("db.system.name", "db.system");
    }

    /** The statement as the instrumentation sanitised it, cut at {@value #MAX_STATEMENT}. */
    public @Nullable String dbStatement() {
        String statement = attr("db.query.text", "db.statement");
        if (statement == null) {
            return null;
        }
        return statement.length() <= MAX_STATEMENT ? statement : statement.substring(0, MAX_STATEMENT);
    }

    public @Nullable String dbNamespace() {
        return attr("db.namespace", "db.name");
    }

    public @Nullable String dbOperation() {
        return attr("db.operation.name", "db.operation");
    }

    public @Nullable String dbTable() {
        return attr("db.collection.name", "db.sql.table");
    }

    // --- derived classification ---

    /**
     * Whether this span is where a request entered the process: a server or
     * consumer span, or a root {@code CLIENT}/{@code PRODUCER} span that is not a
     * database span. A client span starting a trace is a request someone made,
     * which is how the load generator's traffic shows up; a root
     * {@code INTERNAL} span and a root database span are work the application did
     * to itself — a seeder's tens of thousands of {@code INSERT}s are not
     * requests, and counting them would drown the endpoints, the totals, Apdex
     * and {@code check}. Such spans are still stored and still render in the
     * trace tree; they simply are not endpoints.
     */
    public boolean isEntry() {
        if ("SERVER".equals(kind) || "CONSUMER".equals(kind)) {
            return true;
        }
        return parentSpanId == null
                && ("CLIENT".equals(kind) || "PRODUCER".equals(kind))
                && dbSystem() == null;
    }

    /** The exception event, or null. Three sources of error are merged; this is one of them. */
    public @Nullable SpanEvent exceptionEvent() {
        for (SpanEvent event : events) {
            if ("exception".equals(event.name())) {
                return event;
            }
        }
        return null;
    }

    public boolean isError() {
        return "ERROR".equals(status) || exceptionEvent() != null || attributes.containsKey("error.type");
    }

    public @Nullable String errorType() {
        SpanEvent exception = exceptionEvent();
        if (exception != null) {
            Object type = exception.attributes().get("exception.type");
            if (type != null) {
                return String.valueOf(type);
            }
        }
        String errorType = attr("error.type");
        if (errorType != null) {
            return errorType;
        }
        return isError() ? "error" : null;
    }

    public @Nullable String errorMessage() {
        SpanEvent exception = exceptionEvent();
        if (exception != null) {
            Object message = exception.attributes().get("exception.message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return statusMessage;
    }

    public @Nullable String stacktrace() {
        SpanEvent exception = exceptionEvent();
        if (exception == null) {
            return null;
        }
        Object trace = exception.attributes().get("exception.stacktrace");
        return trace == null ? null : String.valueOf(trace);
    }

    /** The colour the waterfall paints this span with. */
    public String category() {
        if (httpMethod() != null || attributes.containsKey("url.full")) {
            return "http";
        }
        if (dbSystem() != null) {
            return "db";
        }
        if (attributes.containsKey("messaging.system")) {
            return "messaging";
        }
        if (attributes.containsKey("rpc.system")) {
            return "rpc";
        }
        return "internal";
    }

    /** The single line the waterfall shows beside the bar. */
    public String summary() {
        switch (category()) {
            case "db" -> {
                String operation = dbOperation();
                String table = dbTable();
                if (operation != null && table != null) {
                    return operation + " " + table;
                }
                String statement = dbStatement();
                if (statement != null) {
                    return statement.length() <= 120 ? statement : statement.substring(0, 120) + "…";
                }
            }
            case "http" -> {
                // A client span is named by its method alone; the URL is what says where it went.
                String url = "CLIENT".equals(kind) ? attr("url.full") : null;
                String head = url == null ? name : name + " " + url;
                Long status = httpStatus();
                return status == null ? head : head + " → " + status;
            }
            default -> {
                // fall through to the span name
            }
        }
        return name;
    }

    /**
     * The endpoint this entry span belongs to.
     *
     * <p>{@code http.route} wins when it is a real route. A servlet mapping of
     * {@code /*} is not one — it says "everything", so every endpoint of a
     * Spring Boot application would collapse into {@code GET /*}. In that case
     * the span name is better: OpenTelemetry already names a server span
     * {@code METHOD route}, or just {@code METHOD} when it knows no route.
     */
    public String endpointName() {
        String route = httpRoute();
        if (route != null && !isWildcardRoute(route)) {
            String method = httpMethod();
            return method == null ? route : method + " " + route;
        }
        return name;
    }

    private static boolean isWildcardRoute(String route) {
        return route.equals("/") || route.equals("/*") || route.endsWith("/*");
    }
}
