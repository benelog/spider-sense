package net.benelog.spidersense.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.Numbers;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Acks;
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
        return heading(what, window, service, requests, 0);
    }

    /**
     * The same line with {@code , 2 acked} after the request count.
     *
     * <p>Only {@code findings} has acknowledgements, and only when there are any:
     * a heading that always said {@code 0 acked} would spend a phrase on nothing
     * (agent.md, "Acknowledgements").
     */
    static String heading(String what, Window window, String service, Long requests, int acked) {
        StringBuilder line = new StringBuilder("# ").append(what).append("  ")
                .append(instant(window.from())).append(" → ").append(clock(window.to()))
                .append("  (").append(range(window)).append(", ")
                .append(service == null ? "all services" : service);
        if (requests != null) {
            line.append(", ").append(requests).append(requests == 1 ? " request" : " requests");
        }
        if (acked > 0) {
            line.append(", ").append(acked).append(" acked");
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
        return findings(window, service, requests, 0, findings, full, otlpEndpoint);
    }

    /**
     * The ranked table and its evidence, with the acknowledged rows named as such.
     *
     * <p>The severity column reads {@code acked} rather than {@code high} for an
     * acknowledged finding: it is still last in the table, and the one word says
     * why without a column of its own (agent.md, "Acknowledgements").
     */
    static String findings(Window window, String service, long requests, int acked,
            List<Findings.Finding> findings, boolean full, String otlpEndpoint) {
        if (findings.isEmpty()) {
            return heading("findings", window, service, requests, acked) + "\n"
                    + empty("findings", window, requests, otlpEndpoint);
        }
        StringBuilder text =
                new StringBuilder(heading("findings", window, service, requests, acked));
        text.append('\n');
        table(text, List.of("#", "severity", "kind", "id", "service", "title"));
        int n = 0;
        for (Findings.Finding finding : findings) {
            n++;
            row(text, List.of(String.valueOf(n),
                    finding.ack() == null ? finding.severity() : "acked",
                    finding.kind(), finding.id(),
                    finding.service(), oneLine(finding.title())));
        }
        n = 0;
        for (Findings.Finding finding : findings) {
            n++;
            text.append('\n').append(n).append(". ").append(finding.id()).append(" — ")
                    .append(finding.why()).append('\n');
            text.append("   ").append(numbers(finding.numbers())).append('\n');
            String hot = hotSpan(finding.numbers());
            if (hot != null) {
                text.append("   ").append(hot).append('\n');
            }
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

    /**
     * {@code requests 3, affected 3, medianRepeats 42}: the numbers on one line.
     *
     * <p>{@code hotSpan} is the one value that is an object rather than a scalar or
     * a list, and it reads as a sentence rather than as a pair, so it gets a line of
     * its own ({@link #hotSpan}) and is left out here.
     */
    private static String numbers(Map<String, Object> numbers) {
        List<String> parts = new ArrayList<>();
        numbers.forEach((key, value) -> {
            if (!HOT_SPAN.equals(key) && !(value instanceof Map<?, ?>)) {
                parts.add(key + " " + scalar(key, value));
            }
        });
        return String.join(", ", parts);
    }

    private static final String HOT_SPAN = "hotSpan";

    /**
     * {@code hot span: SELECT order_line · 312.4 ms self · 62.0%}: where the time
     * went in the finding's first evidence trace (agent.md).
     *
     * @return null when the finding has no hot span, which is a finding with no trace
     */
    private static String hotSpan(Map<String, Object> numbers) {
        if (!(numbers.get(HOT_SPAN) instanceof Map<?, ?> hot)) {
            return null;
        }
        Object selfMs = hot.get("selfMs");
        Object share = hot.get("share");
        return "hot span: " + hot.get("name")
                + " · " + (selfMs instanceof Number self ? Numbers.millis(self.doubleValue()) : "—")
                + " self · "
                + (share instanceof Number part ? Numbers.percent(part.doubleValue()) : "—");
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
            if (key.endsWith("Share") || key.endsWith("Rate")
                    || key.startsWith("share") || key.startsWith("ratio")) {
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

    // --- acknowledgements -----------------------------------------------------

    /** One line, as a mark's is: what happened, to which finding, and why. */
    static String ack(Acks.Ack ack) {
        return "acked " + ack.findingId()
                + (ack.note() == null ? "" : " — " + oneLine(ack.note())) + "\n";
    }

    static String unack(String findingId) {
        return "unacked " + findingId + "\n";
    }

    static String acks(List<Acks.Ack> acks) {
        if (acks.isEmpty()) {
            return "# acks\n\nno acknowledged findings;"
                    + " POST /api/findings/{id}/ack or `ack <finding id>` records one\n";
        }
        StringBuilder text = new StringBuilder("# acks\n\n");
        table(text, List.of("at", "finding", "note"));
        for (Acks.Ack ack : acks) {
            row(text, List.of(instantMillis(ack.at()), ack.findingId(), or(ack.note())));
        }
        return text.toString();
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
     * The trace as an indented tree.
     *
     * <p>A waterfall is a picture; this is the same information as lines, which is
     * what an agent can read. The two numeric columns are the offset from the start
     * of the trace and the span's own duration, and everything below a span line —
     * a statement, an exception, the application frames — is a continuation of it.
     */
    static String trace(Queries.TraceDetail trace, Tingles tingles, CodeFrames frames, boolean full) {
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
        int dbCount = 0;
        int errorCount = 0;
        for (SpanRecord span : trace.spans()) {
            startNs = Math.min(startNs, span.startNanos());
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

        renderSiblings(text, roots, children, 0, null, startNs, tingles, frames, full);

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

    private static void renderSiblings(StringBuilder text, List<SpanRecord> siblings,
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
                line(text, first, depth, parentService, startNs, tingles, total,
                        "  × " + run + ", " + Numbers.millis(total / run) + " avg, "
                                + Numbers.millis(total) + " total");
                if (first.dbStatement() != null) {
                    continuation(text, depth, statement(first.dbStatement(), full));
                }
                i += run;
                continue;
            }
            renderSpan(text, first, children, depth, parentService, startNs, tingles, frames, full);
            i++;
        }
    }

    private static void renderSpan(StringBuilder text, SpanRecord span,
            Map<String, List<SpanRecord>> children, int depth, String parentService, long startNs,
            Tingles tingles, CodeFrames frames, boolean full) {
        line(text, span, depth, parentService, startNs, tingles, span.durationMillis(), "");
        if (span.dbStatement() != null && (full || tingles.isSlow(span))) {
            continuation(text, depth, statement(span.dbStatement(), full));
        }
        if (span.isError()) {
            String message = span.errorMessage();
            continuation(text, depth, "exception " + Findings.simpleName(span.errorType())
                    + (message == null || message.isBlank() ? "" : ": " + oneLine(message)));
            for (String frame : frames.of(span.stacktrace(), span.attributes())) {
                continuation(text, depth, frame);
            }
        }
        List<SpanRecord> kids = children.get(span.spanId());
        if (kids != null) {
            renderSiblings(text, kids, children, depth + 1, span.service(), startNs, tingles, frames,
                    full);
        }
    }

    private static void line(StringBuilder text, SpanRecord span, int depth, String parentService,
            long startNs, Tingles tingles, double durationMs, String suffix) {
        double offsetMs = (span.startNanos() - startNs) / 1_000_000.0;
        text.append(pad(Numbers.millis(offsetMs), OFFSET_WIDTH))
                .append(pad(Numbers.millis(durationMs), DURATION_WIDTH))
                .append("  ".repeat(depth))
                .append(prefix(span))
                .append(span.service().equals(parentService) ? "" : span.service() + " ")
                .append(span.summary())
                .append(suffix);
        if (tingles.isSlow(span)) {
            text.append("  [slow]");
        }
        if (span.isError()) {
            text.append(tingles.isSlow(span) ? " [error]" : "  [error]");
        }
        text.append('\n');
    }

    /** A database span reads as {@code db}; everything else as its span kind. */
    private static String prefix(SpanRecord span) {
        return "db".equals(span.category()) ? "db " : span.kind() + " ";
    }

    private static void continuation(StringBuilder text, int depth, String content) {
        text.append(" ".repeat(OFFSET_WIDTH + DURATION_WIDTH))
                .append("  ".repeat(depth + 1)).append(content).append('\n');
    }

    private static boolean sameLine(SpanRecord one, SpanRecord two) {
        return one.category().equals(two.category())
                && one.service().equals(two.service())
                && one.summary().equals(two.summary());
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
