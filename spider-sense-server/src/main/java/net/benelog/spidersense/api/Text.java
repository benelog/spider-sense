package net.benelog.spidersense.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.Numbers;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingles;

/**
 * The Markdown beside the JSON: what {@link Codecs} is to the UI, this is to an
 * agent.
 *
 * <p>Text first, because an agent reads text and pays for every token (agent.md).
 * Three rules make the difference between a rendering and a dump, and they run
 * through every method here:
 *
 * <ul>
 * <li>It is deterministic. The same data renders to the same bytes, so two
 *     answers can be diffed; nothing in a body depends on when it was rendered,
 *     and {@code now} appears only in the heading.</li>
 * <li>It is complete where an id is concerned. A trace id is 32 characters and a
 *     group id is 12, never elided, because the agent will pass them back.</li>
 * <li>It is small everywhere else. A statement is cut at 200 characters and
 *     repeated sibling spans collapse, unless {@code full=true} says otherwise.</li>
 * </ul>
 */
final class Text {

    static final String CONTENT_TYPE = "text/markdown; charset=utf-8";

    /** The longest statement a line carries unless {@code full} was asked for. */
    private static final int STATEMENT = 200;

    /** A run of identical siblings longer than this collapses into one line. */
    private static final int COLLAPSE_AFTER = 3;

    /** Every digit of a summary is masked before two traces are aligned. */
    private static final Pattern DIGITS = Pattern.compile("\\d");

    private static final int OFFSET_WIDTH = 11;
    private static final int DURATION_WIDTH = 10;

    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter INSTANT_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CLOCK_MILLIS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Text() {
    }

    // --- headings and empty states -------------------------------------------

    /** {@code # findings  2026-09-17T10:00:00+09:00 → 10:15:00  (15m, all services, 120 requests)}. */
    static String heading(String what, Window window, String service, Long requests) {
        StringBuilder line = new StringBuilder("# ").append(what).append("  ")
                .append(instant(window.from())).append(" → ").append(clock(window.to()))
                .append("  (").append(range(window)).append(", ")
                .append(service == null ? "all services" : service);
        if (requests != null) {
            line.append(", ").append(requests).append(requests == 1 ? " request" : " requests");
        }
        return line.append(")\n").toString();
    }

    /**
     * What an empty answer says: what was looked for, and where.
     *
     * <p>With no request at all the answer is not "nothing is wrong" but "nothing
     * arrived", so it also says where to send some.
     */
    static String empty(String what, Window window, long requests, String otlpEndpoint) {
        String line = "no " + what + " since " + instant(window.from())
                + " (" + range(window) + ", " + requests + (requests == 1 ? " request" : " requests")
                + ")\n";
        if (requests == 0 && otlpEndpoint != null) {
            line = line + "\nNothing has been received in this window."
                    + " Send OpenTelemetry data to " + otlpEndpoint + "/v1/traces"
                    + " (metrics and logs beside it).\n";
        }
        return line;
    }

    static String instant(long at) {
        return INSTANT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    static String instantMillis(long at) {
        return INSTANT_MILLIS.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    static String clock(long at) {
        return CLOCK.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    static String clockMillis(long at) {
        return CLOCK_MILLIS.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    /**
     * {@code 15m}, {@code 2h 30m}, {@code 57s}: the window's range, rounded to the
     * second, in the largest units that describe it. A mark-bounded window is
     * rarely a round number, and {@code 57165ms} is not what a reader wants.
     */
    static String range(Window window) {
        long seconds = Math.round(window.rangeMs() / 1000.0);
        if (seconds < 60) {
            return seconds + "s";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        StringBuilder label = new StringBuilder();
        if (days > 0) {
            label.append(days).append('d');
        }
        if (hours > 0) {
            label.append(label.isEmpty() ? "" : " ").append(hours).append('h');
        }
        if (minutes > 0 && days == 0) {
            label.append(label.isEmpty() ? "" : " ").append(minutes).append('m');
        }
        if (rest > 0 && days == 0 && hours == 0) {
            label.append(' ').append(rest).append('s');
        }
        return label.toString();
    }

    // --- status ---------------------------------------------------------------

    static String status(Map<String, String> fields) {
        StringBuilder text = new StringBuilder("# status\n\n");
        table(text, List.of("field", "value"));
        fields.forEach((key, value) -> row(text, List.of(key, value == null ? "—" : value)));
        return text.toString();
    }

    // --- findings -------------------------------------------------------------

    static String findings(Window window, String service, long requests,
            List<Findings.Finding> findings, boolean full, String otlpEndpoint) {
        if (findings.isEmpty()) {
            return heading("findings", window, service, requests) + "\n"
                    + empty("findings", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("findings", window, service, requests));
        text.append('\n');
        table(text, List.of("#", "severity", "kind", "id", "service", "title"));
        int n = 0;
        for (Findings.Finding finding : findings) {
            n++;
            row(text, List.of(String.valueOf(n), finding.severity(), finding.kind(), finding.id(),
                    finding.service(), oneLine(finding.title())));
        }
        n = 0;
        for (Findings.Finding finding : findings) {
            n++;
            text.append('\n').append(n).append(". ").append(finding.id()).append(" — ")
                    .append(finding.why()).append('\n');
            text.append("   ").append(numbers(finding.numbers())).append('\n');
            if (finding.statement() != null) {
                text.append("   ").append(statement(finding.statement(), full)).append('\n');
            }
            for (String frame : finding.code()) {
                text.append("   ").append(frame).append('\n');
            }
            if (!finding.traces().isEmpty()) {
                text.append("   traces: ").append(String.join(" ", finding.traces())).append('\n');
            }
        }
        return text.toString();
    }

    /** {@code requests 3, affected 3, medianRepeats 42}: the numbers on one line. */
    private static String numbers(Map<String, Object> numbers) {
        List<String> parts = new ArrayList<>();
        numbers.forEach((key, value) -> parts.add(key + " " + scalar(key, value)));
        return String.join(", ", parts);
    }

    /**
     * A number said the way its name means it: an instant is a time, a share is a
     * percentage, an Apdex is a score. Without this a {@code firstSeen} would read
     * as {@code 1,789,596,953,808}, which is a number and not an answer.
     */
    private static String scalar(String key, Object value) {
        if (value instanceof Number number) {
            if ("at".equals(key) || key.endsWith("Seen")) {
                return instantMillis(number.longValue());
            }
            if ("apdex".equals(key)) {
                return Numbers.score(number.doubleValue());
            }
            if (key.endsWith("Share") || key.endsWith("Rate")) {
                return Numbers.percent(number.doubleValue());
            }
        }
        return scalar(value);
    }

    private static String scalar(Object value) {
        return switch (value) {
            case null -> "—";
            case Double number -> Numbers.number(number);
            case Float number -> Numbers.number(number.doubleValue());
            case Number number -> Numbers.count(number.longValue());
            case List<?> list -> {
                List<String> parts = new ArrayList<>();
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        List<String> inner = new ArrayList<>();
                        map.forEach((key, each) -> inner.add(key + " " + scalar(String.valueOf(key), each)));
                        parts.add("[" + String.join(" ", inner) + "]");
                    } else {
                        parts.add(String.valueOf(element));
                    }
                }
                yield String.join(" ", parts);
            }
            default -> String.valueOf(value);
        };
    }

    // --- marks ----------------------------------------------------------------

    static String marks(List<Marks.Mark> marks) {
        if (marks.isEmpty()) {
            return "# marks\n\nno marks yet; POST /api/marks or `mark <name>` records one\n";
        }
        StringBuilder text = new StringBuilder("# marks\n\n");
        table(text, List.of("at", "name", "service", "note"));
        for (Marks.Mark mark : marks) {
            row(text, List.of(instantMillis(mark.at()), mark.name(), or(mark.service()), or(mark.note())));
        }
        return text.toString();
    }

    static String mark(Marks.Mark mark) {
        return "mark " + mark.name() + " at " + instantMillis(mark.at())
                + (mark.service() == null ? "" : " (" + mark.service() + ")")
                + (mark.note() == null ? "" : " — " + mark.note()) + "\n";
    }

    // --- compare --------------------------------------------------------------

    static String compare(Compare.Comparison comparison, String service, boolean full) {
        StringBuilder text = new StringBuilder("# compare  ")
                .append(instant(comparison.before().from())).append(" → ")
                .append(clock(comparison.before().to())).append("  vs  ")
                .append(instant(comparison.after().from())).append(" → ")
                .append(clock(comparison.after().to()))
                .append("  (").append(service == null ? "all services" : service).append(")\n\n");

        Stats.Totals before = comparison.beforeTotals();
        Stats.Totals after = comparison.afterTotals();
        table(text, List.of("totals", "before", "after"));
        row(text, List.of("requests", String.valueOf(before.requests()), String.valueOf(after.requests())));
        row(text, List.of("errors", String.valueOf(before.errors()), String.valueOf(after.errors())));
        row(text, List.of("p95", Numbers.millis(before.p95Ms()), Numbers.millis(after.p95Ms())));
        row(text, List.of("apdex", Numbers.score(before.apdex()), Numbers.score(after.apdex())));

        text.append("\n## endpoints\n\n");
        if (comparison.endpoints().isEmpty()) {
            text.append("no endpoint in either window\n");
        } else {
            table(text, List.of("verdict", "endpoint", "id", "calls", "errors", "p95", "db/req",
                    "db ms/req"));
            for (Compare.EndpointDiff diff : comparison.endpoints()) {
                row(text, List.of(diff.verdict(), or(diff.name()), diff.endpointId(),
                        pair(count(diff.before() == null ? null : diff.before().calls()),
                                count(diff.after() == null ? null : diff.after().calls())),
                        pair(count(diff.before() == null ? null : diff.before().errors()),
                                count(diff.after() == null ? null : diff.after().errors())),
                        pair(millis(diff.before() == null ? null : diff.before().p95Ms()),
                                millis(diff.after() == null ? null : diff.after().p95Ms())),
                        pair(number(diff.before() == null ? null : diff.before().dbCallsPerRequest()),
                                number(diff.after() == null ? null : diff.after().dbCallsPerRequest())),
                        pair(millis(diff.before() == null ? null : diff.before().dbMsPerRequest()),
                                millis(diff.after() == null ? null : diff.after().dbMsPerRequest()))));
            }
        }

        text.append("\n## queries\n\n");
        if (comparison.queries().isEmpty()) {
            text.append("no query in either window\n");
        } else {
            table(text, List.of("verdict", "id", "calls", "calls/req", "p95", "total", "statement"));
            for (Compare.QueryDiff diff : comparison.queries()) {
                row(text, List.of(diff.verdict(), diff.queryId(),
                        pair(count(diff.before() == null ? null : diff.before().calls()),
                                count(diff.after() == null ? null : diff.after().calls())),
                        pair(number(diff.before() == null ? null : diff.before().callsPerRequest()),
                                number(diff.after() == null ? null : diff.after().callsPerRequest())),
                        pair(millis(diff.before() == null ? null : diff.before().p95Ms()),
                                millis(diff.after() == null ? null : diff.after().p95Ms())),
                        pair(millis(diff.before() == null ? null : diff.before().totalMs()),
                                millis(diff.after() == null ? null : diff.after().totalMs())),
                        statement(diff.statement(), full)));
            }
        }

        text.append("\n## errors\n\n");
        if (comparison.errors().isEmpty()) {
            text.append("no error in either window\n");
        } else {
            table(text, List.of("verdict", "id", "type", "message", "before", "after"));
            for (Compare.ErrorDiff diff : comparison.errors()) {
                row(text, List.of(diff.verdict(), diff.errorId(), or(diff.type()), or(diff.message()),
                        String.valueOf(diff.before()), String.valueOf(diff.after())));
            }
        }
        return text.toString();
    }

    /** {@code 12 → 3}: the two windows in one cell, a dash where a side has nothing. */
    private static String pair(String before, String after) {
        return before + " → " + after;
    }

    private static String count(Long value) {
        return value == null ? "—" : Numbers.count(value);
    }

    private static String millis(Double value) {
        return value == null ? "—" : Numbers.millis(value);
    }

    private static String number(Double value) {
        return value == null ? "—" : Numbers.number(value);
    }

    // --- check ----------------------------------------------------------------

    static String check(Check.CheckResult result, Window window, String service, String endpoint) {
        String verdict = result.pass() == null ? "no verdict" : result.pass() ? "pass" : "fail";
        StringBuilder text = new StringBuilder("# check  ").append(verdict).append("  ")
                .append(instant(window.from())).append(" → ").append(clock(window.to()))
                .append("  (").append(range(window)).append(", ")
                .append(service == null ? "all services" : service)
                .append(endpoint == null ? "" : ", " + endpoint)
                .append(", ").append(result.requests()).append(" requests)\n\n");
        if (result.reason() != null) {
            text.append(result.reason()).append('\n').append('\n');
        }
        table(text, List.of("rule", "limit", "actual", "verdict", "detail"));
        for (Check.RuleCheck check : result.checks()) {
            row(text, List.of(check.rule(), threshold(check.rule(), check.limit()),
                    check.actual() == null ? "—" : threshold(check.rule(), check.actual()),
                    check.pass() ? "pass" : "fail", check.detail()));
        }
        return text.toString();
    }

    /**
     * A rule's limit and its actual value, said as that rule means them: an Apdex
     * is a score, a count of errors is a count, and a millisecond threshold is a
     * number rather than {@code 500.0}.
     */
    private static String threshold(String rule, double value) {
        if (Check.MIN_APDEX.equals(rule) || Check.MAX_ERROR_RATE.equals(rule)) {
            return Numbers.score(value);
        }
        return value == Math.rint(value) && Math.abs(value) < 1e12
                ? Numbers.count((long) value) : Numbers.number(value);
    }

    // --- lists ----------------------------------------------------------------

    static String traces(Window window, String service, List<Stats.TraceSummary> traces, long total,
            long requests, String otlpEndpoint) {
        if (traces.isEmpty()) {
            return heading("traces", window, service, requests) + "\n"
                    + empty("traces", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("traces", window, service, requests));
        text.append('\n');
        // The table is a page of the newest; the count says how many the window holds.
        text.append(traces.size() < total ? traces.size() + " of " + total : String.valueOf(total))
                .append(total == 1 ? " trace" : " traces").append(", newest first\n\n");
        table(text, List.of("start", "duration", "trace", "root", "service", "spans", "db", "errors",
                "status"));
        for (Stats.TraceSummary trace : traces) {
            row(text, List.of(clockMillis(trace.start()), Numbers.millis(trace.durationMs()),
                    trace.traceId(), or(trace.rootName()), or(trace.rootService()),
                    String.valueOf(trace.spanCount()), String.valueOf(trace.dbCount()),
                    String.valueOf(trace.errorCount()),
                    trace.httpStatus() == null ? "—" : String.valueOf(trace.httpStatus())));
        }
        return text.toString();
    }

    static String endpoints(Window window, String service, List<Stats.EndpointStats> endpoints,
            long requests, String otlpEndpoint) {
        if (endpoints.isEmpty()) {
            return heading("endpoints", window, service, requests) + "\n"
                    + empty("endpoints", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("endpoints", window, service, requests));
        text.append('\n');
        table(text, List.of("endpoint", "id", "service", "calls", "errors", "p50", "p95", "max",
                "total", "apdex"));
        for (Stats.EndpointStats endpoint : endpoints) {
            row(text, List.of(or(endpoint.name()), endpoint.endpointId(), endpoint.service(),
                    Numbers.count(endpoint.calls()), Numbers.count(endpoint.errors()),
                    Numbers.millis(endpoint.p50Ms()), Numbers.millis(endpoint.p95Ms()),
                    Numbers.millis(endpoint.maxMs()), Numbers.millis(endpoint.totalMs()),
                    Numbers.score(endpoint.apdex())));
        }
        return text.toString();
    }

    static String queries(Window window, String service, List<Stats.QueryStats> queries, long requests,
            boolean full, String otlpEndpoint) {
        if (queries.isEmpty()) {
            return heading("queries", window, service, requests) + "\n"
                    + empty("queries", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("queries", window, service, requests));
        text.append('\n');
        table(text, List.of("id", "service", "calls", "slow", "p50", "p95", "max", "total", "callers",
                "statement"));
        for (Stats.QueryStats query : queries) {
            List<String> callers = new ArrayList<>();
            for (Stats.Caller caller : query.callers()) {
                callers.add(caller.endpoint() + " ×" + caller.calls());
            }
            row(text, List.of(query.queryId(), query.service(), Numbers.count(query.calls()),
                    Numbers.count(query.slowCalls()), Numbers.millis(query.p50Ms()),
                    Numbers.millis(query.p95Ms()), Numbers.millis(query.maxMs()),
                    Numbers.millis(query.totalMs()),
                    callers.isEmpty() ? "—" : String.join("; ", callers),
                    statement(query.statement(), full)));
        }
        return text.toString();
    }

    static String errors(Window window, String service, List<Stats.ErrorGroup> errors, long requests,
            boolean full, CodeFrames frames, String otlpEndpoint) {
        if (errors.isEmpty()) {
            return heading("errors", window, service, requests) + "\n"
                    + empty("errors", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("errors", window, service, requests));
        text.append('\n');
        table(text, List.of("id", "service", "type", "message", "count", "first", "last", "endpoints"));
        for (Stats.ErrorGroup group : errors) {
            List<String> endpoints = new ArrayList<>();
            for (Stats.EndpointCount endpoint : group.endpoints()) {
                endpoints.add(endpoint.name() + " ×" + endpoint.count());
            }
            row(text, List.of(group.errorId(), group.service(), or(group.type()), or(group.message()),
                    Numbers.count(group.count()), clockMillis(group.firstSeen()),
                    clockMillis(group.lastSeen()),
                    endpoints.isEmpty() ? "—" : String.join("; ", endpoints)));
        }
        for (Stats.ErrorGroup group : errors) {
            if (group.sample() == null) {
                continue;
            }
            List<String> code = frames.ofStacktrace(group.sample().stacktrace());
            if (code.isEmpty() && !full) {
                continue;
            }
            text.append('\n').append(group.errorId()).append(" — trace ")
                    .append(group.sample().traceId()).append('\n');
            for (String frame : code) {
                text.append("   ").append(frame).append('\n');
            }
        }
        return text.toString();
    }

    static String logs(Window window, String service, List<LogRecord> logs, long total,
            String otlpEndpoint) {
        if (logs.isEmpty()) {
            return heading("logs", window, service, null) + "\n"
                    + empty("logs", window, 0, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("logs", window, service, null));
        text.append('\n')
                .append(logs.size() < total ? logs.size() + " of " + total : String.valueOf(total))
                .append(total == 1 ? " line" : " lines").append(", newest first\n\n");
        for (LogRecord log : logs) {
            text.append(clockMillis(log.at())).append("  ")
                    .append(pad(log.severity(), 6)).append(' ')
                    .append(or(log.logger())).append("  ")
                    .append(oneLine(log.body()));
            if (log.traceId() != null) {
                text.append("  trace ").append(log.traceId());
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String services(Window window, List<Stats.ServiceSummary> services, long requests,
            String otlpEndpoint) {
        if (services.isEmpty()) {
            return heading("services", window, null, requests) + "\n"
                    + empty("services", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("services", window, null, requests));
        text.append('\n');
        table(text, List.of("service", "language", "embedded", "requests", "errors", "p50", "p95",
                "max", "apdex", "last seen"));
        for (Stats.ServiceSummary summary : services) {
            Stats.Totals totals = summary.totals();
            row(text, List.of(summary.name(), or(summary.language()),
                    summary.embedded() ? "yes" : "no", Numbers.count(totals.requests()),
                    Numbers.count(totals.errors()), Numbers.millis(totals.p50Ms()),
                    Numbers.millis(totals.p95Ms()), Numbers.millis(totals.maxMs()),
                    Numbers.score(totals.apdex()), instantMillis(summary.lastSeen())));
        }
        return text.toString();
    }

    // --- one trace ------------------------------------------------------------

    /**
     * One line of the trace rendering: a span, or a run of identical siblings
     * collapsed into one.
     *
     * <p>The tree becomes this list before anything is printed, because the trace
     * diff aligns two traces on exactly the lines the single rendering would have
     * shown: a collapsed group is one line there and one line here, so the two
     * renderings can never disagree about what a line is (agent.md).
     *
     * @param text  the span as it reads without its columns: the kind or
     *              {@code db}, the service where it changes from the parent, and
     *              the summary
     * @param count how many siblings the line stands for; 1 for a span of its own
     * @param under the continuations below it — the statement, the exception and
     *              the application frames
     */
    record TraceLine(int depth, SpanRecord span, String text, double offsetMs, double durationMs,
            int count, List<String> under) {

        /**
         * What the diff aligns on: where the line sits and what it says, with its
         * timing, its count and its digits left out, so {@code /api/books/155} and
         * {@code /api/books/87} are the same line of the same tree.
         */
        String key() {
            return depth + " " + span.category() + " "
                    + DIGITS.matcher(span.summary()).replaceAll("?");
        }
    }

    /**
     * The trace as an indented tree.
     *
     * <p>A waterfall is a picture; this is the same information as lines, which is
     * what an agent can read. The two numeric columns are the offset from the start
     * of the trace and the span's own duration, and everything below a span line —
     * a statement, an exception, the application frames — is a continuation of it.
     */
    static String trace(Queries.TraceDetail trace, Tingles tingles, CodeFrames frames, boolean full) {
        int dbCount = 0;
        int errorCount = 0;
        for (SpanRecord span : trace.spans()) {
            if (span.dbStatement() != null) {
                dbCount++;
            }
            if (span.isError()) {
                errorCount++;
            }
        }

        StringBuilder text = new StringBuilder("# trace ").append(trace.traceId()).append("  ")
                .append(instantMillis(trace.start())).append("  ")
                .append(Numbers.millis(trace.durationMs())).append("  ")
                .append(String.join(" → ", trace.services())).append("  ")
                .append(trace.spans().size()).append(" spans, ").append(dbCount).append(" db, ")
                .append(errorCount).append(errorCount == 1 ? " error" : " errors").append("\n\n");
        text.append(pad("offset", OFFSET_WIDTH)).append(pad("duration", DURATION_WIDTH))
                .append("span\n");

        for (TraceLine line : lines(trace, tingles, frames, full)) {
            text.append(pad(Numbers.millis(line.offsetMs()), OFFSET_WIDTH))
                    .append(pad(Numbers.millis(line.durationMs()), DURATION_WIDTH))
                    .append("  ".repeat(line.depth()))
                    .append(line.text());
            if (line.count() > 1) {
                text.append("  × ").append(line.count()).append(", ")
                        .append(Numbers.millis(line.durationMs() / line.count())).append(" avg, ")
                        .append(Numbers.millis(line.durationMs())).append(" total");
            }
            if (tingles.isSlow(line.span())) {
                text.append("  [slow]");
            }
            if (line.span().isError()) {
                text.append(tingles.isSlow(line.span()) ? " [error]" : "  [error]");
            }
            text.append('\n');
            for (String content : line.under()) {
                continuation(text, OFFSET_WIDTH + DURATION_WIDTH, line.depth(), content);
            }
        }

        if (!trace.logs().isEmpty()) {
            text.append("\nlogs (").append(trace.logs().size()).append(")\n");
            for (LogRecord log : trace.logs()) {
                text.append(clockMillis(log.at())).append("  ").append(pad(log.severity(), 6))
                        .append(' ').append(or(log.logger())).append("  ")
                        .append(oneLine(log.body())).append('\n');
            }
        }
        return text.toString();
    }

    /** The lines of one trace, in the order the tree prints them. */
    static List<TraceLine> lines(Queries.TraceDetail trace, Tingles tingles, CodeFrames frames,
            boolean full) {
        Map<String, List<SpanRecord>> children = new LinkedHashMap<>();
        List<SpanRecord> roots = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (SpanRecord span : trace.spans()) {
            ids.add(span.spanId());
        }
        for (SpanRecord span : trace.spans()) {
            if (span.parentSpanId() == null || !ids.contains(span.parentSpanId())) {
                roots.add(span);
            } else {
                children.computeIfAbsent(span.parentSpanId(), id -> new ArrayList<>()).add(span);
            }
        }
        long startNs = Long.MAX_VALUE;
        for (SpanRecord span : trace.spans()) {
            startNs = Math.min(startNs, span.startNanos());
        }
        List<TraceLine> lines = new ArrayList<>();
        collect(lines, roots, children, 0, null, startNs, tingles, frames, full);
        return lines;
    }

    private static void collect(List<TraceLine> lines, List<SpanRecord> siblings,
            Map<String, List<SpanRecord>> children, int depth, String parentService, long startNs,
            Tingles tingles, CodeFrames frames, boolean full) {
        siblings.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
        int i = 0;
        while (i < siblings.size()) {
            SpanRecord first = siblings.get(i);
            int run = 1;
            while (i + run < siblings.size() && sameLine(first, siblings.get(i + run))
                    && !children.containsKey(siblings.get(i + run).spanId())) {
                run++;
            }
            boolean collapse = !full && run > COLLAPSE_AFTER
                    && !children.containsKey(first.spanId());
            if (collapse) {
                double total = 0;
                for (int j = i; j < i + run; j++) {
                    total += siblings.get(j).durationMillis();
                }
                lines.add(new TraceLine(depth, first, spanText(first, parentService),
                        offset(first, startNs), total, run,
                        first.dbStatement() == null ? List.of()
                                : List.of(statement(first.dbStatement(), full))));
                i += run;
                continue;
            }
            List<String> under = new ArrayList<>();
            if (first.dbStatement() != null && (full || tingles.isSlow(first))) {
                under.add(statement(first.dbStatement(), full));
            }
            if (first.isError()) {
                String message = first.errorMessage();
                under.add("exception " + Findings.simpleName(first.errorType())
                        + (message == null || message.isBlank() ? "" : ": " + oneLine(message)));
                under.addAll(frames.of(first.stacktrace(), first.attributes()));
            }
            lines.add(new TraceLine(depth, first, spanText(first, parentService),
                    offset(first, startNs), first.durationMillis(), 1, List.copyOf(under)));
            List<SpanRecord> kids = children.get(first.spanId());
            if (kids != null) {
                collect(lines, kids, children, depth + 1, first.service(), startNs, tingles, frames,
                        full);
            }
            i++;
        }
    }

    private static double offset(SpanRecord span, long startNs) {
        return (span.startNanos() - startNs) / 1_000_000.0;
    }

    /** The span as the tree says it; the service is named only where it changes. */
    private static String spanText(SpanRecord span, String parentService) {
        return prefix(span)
                + (span.service().equals(parentService) ? "" : span.service() + " ")
                + span.summary();
    }

    /** A database span reads as {@code db}; everything else as its span kind. */
    private static String prefix(SpanRecord span) {
        return "db".equals(span.category()) ? "db " : span.kind() + " ";
    }

    private static void continuation(StringBuilder text, int columns, int depth, String content) {
        text.append(" ".repeat(columns)).append("  ".repeat(depth + 1)).append(content).append('\n');
    }

    private static boolean sameLine(SpanRecord one, SpanRecord two) {
        return one.category().equals(two.category())
                && one.service().equals(two.service())
                && one.summary().equals(two.summary());
    }

    // --- two traces aligned ---------------------------------------------------

    /** How many lines of either side the alignment considers (agent.md). */
    private static final int DIFF_LINES = 2_000;

    /** Each of the two duration columns is as wide as the trace's offset column. */
    private static final int DIFF_WIDTH = OFFSET_WIDTH;

    /** The gutter and both columns: where a span line begins, and a continuation under it. */
    private static final int DIFF_COLUMNS = 3 + DIFF_WIDTH + DIFF_WIDTH;

    /** A delta's minus is U+2212, which is as wide as the digits it stands before. */
    private static final String MINUS = "−";

    /**
     * One line of the aligned rendering: the same line on both sides, or on one.
     *
     * @param counted whether the line says how many spans it stands for, which a
     *                collapsed group does even where the other side collapsed
     *                nothing — a count that changed is the whole finding
     */
    record DiffLine(char op, TraceLine a, TraceLine b, boolean counted) {

        /** The side the line is rendered from: {@code a} where there is one. */
        TraceLine either() {
            return a == null ? b : a;
        }

        /**
         * The statement or the exception under the line. Timing is diffed and prose
         * is not, so a matched line shows {@code a}'s continuations, or {@code b}'s
         * when only {@code b} has any (agent.md).
         */
        List<String> under() {
            if (a != null && !a.under().isEmpty()) {
                return a.under();
            }
            return b == null ? List.of() : b.under();
        }
    }

    /**
     * The two line sequences aligned by their longest common subsequence.
     *
     * <p>The key is the line's shape ({@link TraceLine#key()}), never its timing:
     * the question the diff answers is which span went away and which one got
     * slower, and a span that took 38 ms before and 2 ms after is the same span.
     * Each side is cut at {@value #DIFF_LINES} lines because the alignment is
     * quadratic and a trace that long is a different problem.
     */
    static List<DiffLine> align(List<TraceLine> first, List<TraceLine> second) {
        List<TraceLine> left = first.size() <= DIFF_LINES ? first : first.subList(0, DIFF_LINES);
        List<TraceLine> right = second.size() <= DIFF_LINES ? second : second.subList(0, DIFF_LINES);
        int n = left.size();
        int m = right.size();
        String[] keysLeft = new String[n];
        String[] keysRight = new String[m];
        for (int i = 0; i < n; i++) {
            keysLeft[i] = left.get(i).key();
        }
        for (int j = 0; j < m; j++) {
            keysRight[j] = right.get(j).key();
        }
        int[][] common = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = keysLeft[i].equals(keysRight[j])
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }

        List<DiffLine> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (keysLeft[i].equals(keysRight[j])) {
                matched(lines, left.get(i), right.get(j));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                lines.add(new DiffLine('-', left.get(i), null, left.get(i).count() > 1));
                i++;
            } else {
                lines.add(new DiffLine('+', null, right.get(j), right.get(j).count() > 1));
                j++;
            }
        }
        while (i < n) {
            lines.add(new DiffLine('-', left.get(i), null, left.get(i).count() > 1));
            i++;
        }
        while (j < m) {
            lines.add(new DiffLine('+', null, right.get(j), right.get(j).count() > 1));
            j++;
        }
        return lines;
    }

    /**
     * A line both sides have. Where the two collapsed a different number of
     * siblings it is written as two lines, {@code -} with {@code a}'s count and
     * {@code +} with {@code b}'s: the counts are what changed, and one row showing
     * both would hide it (agent.md).
     */
    private static void matched(List<DiffLine> lines, TraceLine a, TraceLine b) {
        if (a.count() == b.count()) {
            lines.add(new DiffLine('=', a, b, a.count() > 1));
            return;
        }
        lines.add(new DiffLine('-', a, null, true));
        lines.add(new DiffLine('+', null, b, true));
    }

    /**
     * Two traces as one text: the gutter, {@code a}'s duration, {@code b}'s, and
     * the span line indented by depth.
     *
     * <p>No logs, and no slow or error marker: what is on both sides would not say
     * whose marker it is, and an error is already said by the exception under the
     * line.
     */
    static String traceDiff(Queries.TraceDetail a, Queries.TraceDetail b, List<DiffLine> lines) {
        double delta = b.durationMs() - a.durationMs();
        StringBuilder text = new StringBuilder("# trace diff ").append(a.traceId())
                .append(" → ").append(b.traceId()).append("  ")
                .append(Numbers.millis(a.durationMs())).append(" → ")
                .append(Numbers.millis(b.durationMs()))
                .append("  (").append(signed(delta)).append(", ")
                .append(signedPercent(delta, a.durationMs())).append(")  ")
                .append(a.spans().size()).append(" → ").append(b.spans().size())
                .append(" spans\n\n");
        text.append("   ").append(pad("a", DIFF_WIDTH)).append(pad("b", DIFF_WIDTH)).append("span\n");

        for (DiffLine line : lines) {
            TraceLine shown = line.either();
            text.append(line.op()).append("  ")
                    .append(pad(side(line.a()), DIFF_WIDTH))
                    .append(pad(side(line.b()), DIFF_WIDTH))
                    .append("  ".repeat(shown.depth()))
                    .append(shown.text());
            if (line.counted()) {
                text.append("  × ").append(shown.count());
            }
            text.append('\n');
            for (String content : line.under()) {
                continuation(text, DIFF_COLUMNS, shown.depth(), content);
            }
        }
        return text.toString();
    }

    /** One side's duration, or a dash where that side has no such span. */
    private static String side(TraceLine line) {
        return line == null ? "—" : Numbers.millis(line.durationMs());
    }

    /** {@code −271.2 ms}: a delta always carries its sign, even when it is zero. */
    private static String signed(double delta) {
        return (delta < 0 ? MINUS : "+") + Numbers.millis(Math.abs(delta));
    }

    /** The same delta as a share of where it started, or a dash over nothing. */
    private static String signedPercent(double delta, double base) {
        if (base <= 0) {
            return "—";
        }
        return (delta < 0 ? MINUS : "+") + Numbers.percent(Math.abs(delta) / base);
    }

    // --- small pieces ---------------------------------------------------------

    // --- sql ------------------------------------------------------------------

    /**
     * {@code # sql  12 rows} and the table under it.
     *
     * <p>No window and no clock: the answer to an arbitrary statement is whatever
     * the statement asked for, and the one thing the heading can honestly say is
     * how many rows came back and whether the cap cut them off. How long it took
     * is in the JSON only, because a body that changed between two identical runs
     * would stop being diffable (agent.md).
     */
    static String sql(ReadOnlyQuery.Result result, int limit, boolean full) {
        StringBuilder text = new StringBuilder("# sql  ")
                .append(result.rows().size()).append(" rows");
        if (result.truncated()) {
            text.append(" (truncated at ").append(limit).append(')');
        }
        text.append("\n\n");
        List<String> header = new ArrayList<>(result.columns().size());
        for (String column : result.columns()) {
            header.add(oneLine(column));
        }
        table(text, header);
        for (List<Object> row : result.rows()) {
            List<String> cells = new ArrayList<>(row.size());
            for (Object value : row) {
                cells.add(cell(value, full));
            }
            row(text, cells);
        }
        return text.toString();
    }

    /**
     * A cell of a SQL answer, said the way the store holds it.
     *
     * <p>No thousands separator and no rounding: these are the values of whatever
     * the statement selected, an epoch millisecond as often as a duration, and a
     * number a reader has to pass back into another statement must survive the
     * round trip. Text is cut like a statement, at 200 characters unless
     * {@code full}.
     */
    private static String cell(Object value, boolean full) {
        return switch (value) {
            case null -> "—";
            case Boolean flag -> String.valueOf(flag);
            case Long number -> String.valueOf(number);
            case Double number -> String.valueOf(number);
            default -> statement(String.valueOf(value), full);
        };
    }

    static String statement(String statement, boolean full) {
        if (statement == null) {
            return "—";
        }
        String single = oneLine(statement);
        return full || single.length() <= STATEMENT ? single : single.substring(0, STATEMENT) + "…";
    }

    /** A table cell cannot carry a newline or a bar. */
    static String oneLine(String value) {
        return value == null ? "—" : value.replaceAll("\\s+", " ").replace("|", "\\|").trim();
    }

    private static String or(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void table(StringBuilder text, List<String> columns) {
        row(text, columns);
        List<String> rule = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            rule.add("---");
        }
        row(text, rule);
    }

    private static void row(StringBuilder text, List<String> cells) {
        text.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }
}
