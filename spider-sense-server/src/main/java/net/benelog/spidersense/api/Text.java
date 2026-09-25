package net.benelog.spidersense.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.Numbers;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.SchemaBlock;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Ids;
import net.benelog.spidersense.store.Importer;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingles;
import org.jspecify.annotations.Nullable;

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
    private static final int STATEMENT = Limits.STATEMENT_CHARS;

    /** A run of identical siblings longer than this collapses into one line. */
    private static final int COLLAPSE_AFTER = 3;

    /**
     * One column of a table: its header and how a row's cell is written, on one line, so a column
     * cannot be added to the header without its cell or land under another column's header.
     */
    private record Column<T>(String header, Function<T, String> cell) {
    }

    /** A finding and its place in the list, which is the table's first column. */
    private record Ranked(int rank, Findings.Finding finding) {
    }

    /** The columns of the findings table, one finding's included. */
    private static final List<Column<Ranked>> FINDING_COLUMNS = List.of(
            new Column<>("#", ranked -> String.valueOf(ranked.rank())),
            new Column<>("severity", ranked -> severity(ranked.finding())),
            new Column<>("state", ranked -> ranked.finding().state()),
            new Column<>("kind", ranked -> ranked.finding().kind()),
            new Column<>("id", ranked -> ranked.finding().id()),
            new Column<>("service", ranked -> ranked.finding().service()),
            new Column<>("title", ranked -> ranked.finding().title()));

    /** The columns of the queries table, one query group's included; {@code full} keeps statements whole. */
    private static List<Column<Stats.QueryStats>> queryColumns(boolean full) {
        return List.of(
                new Column<>("id", Stats.QueryStats::queryId),
                new Column<>("service", Stats.QueryStats::service),
                new Column<>("calls", query -> Numbers.count(query.calls())),
                new Column<>("slow", query -> Numbers.count(query.slowCalls())),
                new Column<>("p50", query -> Numbers.millis(query.p50Ms())),
                new Column<>("p95", query -> Numbers.millis(query.p95Ms())),
                new Column<>("max", query -> Numbers.millis(query.maxMs())),
                new Column<>("total", query -> Numbers.millis(query.totalMs())),
                new Column<>("callers", Text::callers),
                new Column<>("unindexed", query -> unindexed(query.schema())),
                new Column<>("statement", query -> cutToStatement(query.statement(), full)));
    }

    /** The columns of the errors table, one error group's included. */
    private static final List<Column<Stats.ErrorGroup>> ERROR_COLUMNS = List.of(
            new Column<>("id", Stats.ErrorGroup::errorId),
            new Column<>("service", Stats.ErrorGroup::service),
            new Column<>("type", group -> orDash(group.type())),
            new Column<>("message", group -> orDash(group.message())),
            new Column<>("count", group -> Numbers.count(group.count())),
            new Column<>("first", group -> clockMillis(group.firstSeen())),
            new Column<>("last", group -> clockMillis(group.lastSeen())),
            new Column<>("endpoints", Text::endpointCounts));

    private static final List<Column<Stats.TraceSummary>> TRACE_COLUMNS = List.of(
            new Column<>("start", trace -> clockMillis(trace.start())),
            new Column<>("duration", trace -> Numbers.millis(trace.durationMs())),
            new Column<>("trace", Stats.TraceSummary::traceId),
            new Column<>("root", trace -> orDash(trace.rootName())),
            new Column<>("service", trace -> orDash(trace.rootService())),
            new Column<>("spans", trace -> String.valueOf(trace.spanCount())),
            new Column<>("db", trace -> String.valueOf(trace.dbCount())),
            new Column<>("errors", trace -> String.valueOf(trace.errorCount())),
            new Column<>("status", trace -> trace.httpStatus() == null
                    ? "—" : String.valueOf(trace.httpStatus())));

    private static final List<Column<Stats.EndpointStats>> ENDPOINT_COLUMNS = List.of(
            new Column<>("endpoint", endpoint -> orDash(endpoint.name())),
            new Column<>("id", Stats.EndpointStats::endpointId),
            new Column<>("service", Stats.EndpointStats::service),
            new Column<>("calls", endpoint -> Numbers.count(endpoint.calls())),
            new Column<>("errors", endpoint -> Numbers.count(endpoint.errors())),
            new Column<>("p50", endpoint -> Numbers.millis(endpoint.p50Ms())),
            new Column<>("p95", endpoint -> Numbers.millis(endpoint.p95Ms())),
            new Column<>("max", endpoint -> Numbers.millis(endpoint.maxMs())),
            new Column<>("total", endpoint -> Numbers.millis(endpoint.totalMs())),
            new Column<>("apdex", endpoint -> Numbers.score(endpoint.apdex())));

    private static final List<Column<Stats.ServiceSummary>> SERVICE_COLUMNS = List.of(
            new Column<>("service", Stats.ServiceSummary::name),
            new Column<>("language", summary -> orDash(summary.language())),
            new Column<>("embedded", summary -> summary.embedded() ? "yes" : "no"),
            new Column<>("requests", summary -> Numbers.count(summary.totals().requests())),
            new Column<>("errors", summary -> Numbers.count(summary.totals().errors())),
            new Column<>("p50", summary -> Numbers.millis(summary.totals().p50Ms())),
            new Column<>("p95", summary -> Numbers.millis(summary.totals().p95Ms())),
            new Column<>("max", summary -> Numbers.millis(summary.totals().maxMs())),
            new Column<>("apdex", summary -> Numbers.score(summary.totals().apdex())),
            new Column<>("last seen", summary -> instantMillis(summary.lastSeen())));

    /** The endpoint rows of a compare: each number as {@code before → after}. */
    private static final List<Column<Compare.EndpointDiff>> COMPARE_ENDPOINT_COLUMNS = List.of(
            new Column<>("verdict", Compare.EndpointDiff::verdict),
            new Column<>("endpoint", diff -> orDash(diff.name())),
            new Column<>("id", Compare.EndpointDiff::endpointId),
            endpointPair("calls", Compare.Side::calls, Text::count),
            endpointPair("errors", Compare.Side::errors, Text::count),
            endpointPair("p95", Compare.Side::p95Ms, Text::millis),
            endpointPair("db/req", Compare.Side::dbCallsPerRequest, Text::number),
            endpointPair("db ms/req", Compare.Side::dbMsPerRequest, Text::millis));

    private static <V> Column<Compare.EndpointDiff> endpointPair(String header, Function<Compare.Side, V> field,
            Function<@Nullable V, String> format) {
        return new Column<>(header, diff -> pair(diff.before(), diff.after(), field, format));
    }

    /** The query rows of a compare; {@code full} keeps statements whole. */
    private static List<Column<Compare.QueryDiff>> compareQueryColumns(boolean full) {
        return List.of(
                new Column<>("verdict", Compare.QueryDiff::verdict),
                new Column<>("id", Compare.QueryDiff::queryId),
                queryPair("calls", Compare.QuerySide::calls, Text::count),
                queryPair("calls/req", Compare.QuerySide::callsPerRequest, Text::number),
                queryPair("p95", Compare.QuerySide::p95Ms, Text::millis),
                queryPair("total", Compare.QuerySide::totalMs, Text::millis),
                new Column<>("statement", diff -> cutToStatement(diff.statement(), full)));
    }

    private static <V> Column<Compare.QueryDiff> queryPair(String header, Function<Compare.QuerySide, V> field,
            Function<@Nullable V, String> format) {
        return new Column<>(header, diff -> pair(diff.before(), diff.after(), field, format));
    }

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
    static String heading(String what, Window window, @Nullable String service,
            @Nullable Long requests) {
        return heading(what, window, service, requests, 0, 0);
    }

    /**
     * The same line with {@code , 2 acked, 1 resolved} after the request count.
     *
     * <p>Only {@code findings} has acknowledgements and resolutions, and only when
     * there are any:
     * a heading that always said {@code 0 acked} would spend a phrase on nothing
     * (agent.md, "Acknowledgements").
     */
    static String heading(String what, Window window, @Nullable String service,
            @Nullable Long requests, int acked, int resolved) {
        return headingOf(what, window, scope(service), requests, acked, resolved);
    }

    /** The heading with its scope already said, which {@code check} extends with an endpoint. */
    private static String headingOf(String what, Window window, String scope,
            @Nullable Long requests, int acked, int resolved) {
        StringBuilder line = new StringBuilder("# ").append(what).append("  ")
                .append(interval(window.from(), window.to()))
                .append("  (").append(range(window)).append(", ")
                .append(scope);
        if (requests != null) {
            line.append(", ").append(plural(requests, "request"));
        }
        if (acked > 0) {
            line.append(", ").append(acked).append(" acked");
        }
        if (resolved > 0) {
            line.append(", ").append(resolved).append(" resolved");
        }
        return line.append(")\n").toString();
    }

    /**
     * What an empty answer says: what was looked for, and where.
     *
     * <p>With no request at all the answer is not "nothing is wrong" but "nothing
     * arrived", so it also says where to send some.
     */
    static String empty(String what, Window window, long requests,
            @Nullable String otlpEndpoint) {
        String line = "no " + what + " since " + instant(window.from())
                + " (" + range(window) + ", " + plural(requests, "request") + ")\n";
        if (requests == 0 && otlpEndpoint != null) {
            line = line + "\nNothing has been received in this window."
                    + " Send OpenTelemetry data to " + otlpEndpoint + "/v1/traces"
                    + " (metrics and logs beside it).\n";
        }
        return line;
    }

    /** {@code 2026-09-17T10:00:00+09:00 → 10:15:00}: two instants, the second as a time of day. */
    static String interval(long from, long to) {
        return instant(from) + " → " + clock(to);
    }

    /** {@code all services}, or the one service an answer is narrowed to. */
    static String scope(@Nullable String service) {
        return service == null ? "all services" : service;
    }

    /** {@code 1 request}, {@code 3 requests}: a count as written, with its noun. */
    static String plural(long n, String noun) {
        return n + " " + (n == 1 ? noun : noun + "s");
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

    /** {@code status} as the table of cli.adoc#status: the fields a reader asks about, in order. */
    static String status(StatusSnapshot status) {
        Map<String, @Nullable String> fields = new LinkedHashMap<>();
        fields.put("name", Version.NAME + " " + Version.CURRENT);
        fields.put("mode", status.mode());
        fields.put("endpoint", status.endpoint());
        fields.put("started", status.startedAt() <= 0 ? null : instantMillis(status.startedAt()));
        fields.put("embedded service", status.embeddedService());
        fields.put("thresholds", "slow request " + status.slowRequestMs() + " ms, slow query "
                + status.slowQueryMs() + " ms");
        fields.put("ignore", status.ignoredEndpoints().isEmpty() ? "none"
                : String.join(", ", status.ignoredEndpoints()));
        fields.put("retention", status.retentionHours() + " hours, " + status.retentionSpans() + " spans");
        if (status.maxSpansPerSecond() != null) {
            fields.put("ingest cap", status.maxSpansPerSecond() + " spans/s");
        }
        Database.Storage storage = status.storage();
        fields.put("database", storage.path() == null ? storage.url() : storage.path());
        fields.put("database size", storage.sizeBytes() + " bytes");
        if (storage.fallback()) {
            fields.put("fallback", storage.fallbackReason());
        }
        fields.put("dropped spans", String.valueOf(status.droppedSpans()));
        fields.put("spans", String.valueOf(status.spans()));
        fields.put("traces", String.valueOf(status.traces()));
        fields.put("logs", String.valueOf(status.logs()));
        fields.put("metric series", String.valueOf(status.metricSeries()));
        fields.put("services", String.valueOf(status.services()));
        fields.put("oldest span", status.oldestSpan() <= 0 ? null : instantMillis(status.oldestSpan()));
        return status(fields);
    }

    /** A {@code field | value} table under the {@code status} heading. */
    static String status(Map<String, @Nullable String> fields) {
        StringBuilder text = new StringBuilder("# status\n\n");
        table(text, List.of("field", "value"));
        fields.forEach((key, value) -> row(text, List.of(key, value == null ? "—" : value)));
        return text.toString();
    }

    // --- findings -------------------------------------------------------------

    static String findings(Window window, @Nullable String service, long requests,
            List<Findings.Finding> findings, boolean full, @Nullable String otlpEndpoint) {
        return findings(window, service, requests, 0, 0, findings, full, otlpEndpoint);
    }

    /**
     * The ranked table and its evidence, with the acknowledged rows named as such.
     *
     * <p>The severity column reads {@code acked} rather than {@code high} for an
     * acknowledged finding, and {@code resolved} for a resolved one that has not
     * come back: it is still last in the table, and the one word says why without
     * a column of its own (agent.md, "Acknowledgements"). The state column says
     * what the last restart changed (agent.md, "State").
     */
    static String findings(Window window, @Nullable String service, long requests, int acked,
            int resolved, List<Findings.Finding> findings, boolean full,
            @Nullable String otlpEndpoint) {
        if (findings.isEmpty()) {
            return heading("findings", window, service, requests, acked, resolved) + "\n"
                    + empty("findings", window, requests, otlpEndpoint);
        }
        StringBuilder text =
                new StringBuilder(heading("findings", window, service, requests, acked, resolved));
        text.append('\n');
        List<Ranked> ranked = new ArrayList<>(findings.size());
        for (Findings.Finding finding : findings) {
            ranked.add(new Ranked(ranked.size() + 1, finding));
        }
        table(text, FINDING_COLUMNS, ranked);
        for (Ranked each : ranked) {
            findingEvidence(text, each.rank(), each.finding(), full);
        }
        return text.toString();
    }

    /**
     * One finding as the list renders it: the list's heading with the id in place
     * of the word, the table with its one row, and its evidence block, numbered by
     * its rank in the list (agent.md, "One finding"). Both parts are written by the
     * code that writes them for the list, so they are the list's bytes.
     */
    static String finding(Window window, @Nullable String service, long requests, int rank,
            Findings.Finding finding, boolean full) {
        StringBuilder text = new StringBuilder(heading("finding " + finding.id(), window, service,
                requests));
        text.append('\n');
        table(text, FINDING_COLUMNS, List.of(new Ranked(rank, finding)));
        findingEvidence(text, rank, finding, full);
        return text.toString();
    }

    private static void findingEvidence(StringBuilder text, int rank, Findings.Finding finding,
            boolean full) {
        // The why and the numbers carry what the application wrote (an exception message,
        // a log body), so each is kept to its one line.
        text.append('\n').append(rank).append(". ").append(finding.id()).append(" — ")
                .append(singleLine(finding.why())).append('\n');
        text.append("   ").append(numbers(finding.numbers())).append('\n');
        String hot = hotSpan(finding.numbers());
        if (hot != null) {
            text.append("   ").append(hot).append('\n');
        }
        whereTheTimeWent(text, finding.numbers());
        if (finding.statement() != null) {
            text.append("   ").append(statementLine(finding.statement(), full)).append('\n');
        }
        schema(text, finding.schema());
        for (String frame : finding.code()) {
            text.append("   ").append(frame).append('\n');
        }
        if (!finding.traces().isEmpty()) {
            text.append("   traces: ").append(String.join(" ", finding.traces())).append('\n');
        }
    }

    /** {@code acked}, {@code resolved} for one set aside because it has not come back, or the severity. */
    private static String severity(Findings.Finding finding) {
        if (finding.ack() != null) {
            return "acked";
        }
        return finding.setAside() ? "resolved" : finding.severity();
    }

    /**
     * The schema block, between the statement and the code frames (agent.md).
     *
     * <p>One line per table, then one line for the columns, because the question a
     * reader arrives with is "which column should I index" and the answer is the
     * second line; the first is what is already there, so that the answer can be
     * checked rather than believed. A finding without a block prints nothing.
     */
    private static void schema(StringBuilder text, @Nullable SchemaBlock block) {
        if (block == null) {
            return;
        }
        for (SchemaBlock.Table table : block.tables()) {
            List<String> indexes = new ArrayList<>();
            for (SchemaBlock.Index index : table.indexes()) {
                indexes.add(index.name() + " (" + String.join(", ", index.columns()) + ")"
                        + (index.unique() ? " unique" : ""));
            }
            text.append("   indexes ").append(table.table()).append(": ")
                    .append(indexes.isEmpty() ? "none" : String.join(", ", indexes)).append('\n');
        }
        text.append("   predicates: ");
        if (block.predicates().isEmpty()) {
            text.append("none");
        } else {
            text.append(String.join(", ", block.predicates())).append("; unindexed: ")
                    .append(block.unindexed().isEmpty() ? "none"
                            : String.join(", ", block.unindexed()));
        }
        text.append('\n');
    }

    /** The unindexed columns of a query group, as the {@code queries} table shows them. */
    private static String unindexed(@Nullable SchemaBlock block) {
        if (block == null) {
            return "—";
        }
        return block.unindexed().isEmpty() ? "none" : String.join(", ", block.unindexed());
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
            if (!LINES_OF_THEIR_OWN.contains(key) && !(value instanceof Map<?, ?>)) {
                parts.add(key + " " + formatByKey(key, value));
            }
        });
        return String.join(", ", parts);
    }

    private static final String HOT_SPAN = "hotSpan";
    private static final String HOT_SPANS = "hotSpans";
    private static final String BREAKDOWN = "breakdown";

    /** The values that read as a sentence rather than as a pair, and get their own line. */
    private static final Set<String> LINES_OF_THEIR_OWN = Set.of(HOT_SPAN, HOT_SPANS, BREAKDOWN);

    /**
     * {@code hot span: SELECT order_line · 312.4 ms self · 62.0%}: where the time
     * went in the finding's first evidence trace (agent.md).
     *
     * @return null when the finding has no hot span, which is a finding with no trace
     */
    private static @Nullable String hotSpan(Map<String, Object> numbers) {
        if (!(numbers.get(HOT_SPAN) instanceof Map<?, ?> hot)) {
            return null;
        }
        return "hot span: " + hotSpanLine(hot) + " self · " + percent(hot.get("share"));
    }

    /** {@code SELECT order_line · 312.4 ms}: a hot span's name and its self time. */
    private static String hotSpanLine(Map<?, ?> hot) {
        return singleLine(String.valueOf(hot.get("name"))) + " · " + millis(hot.get("selfMs"));
    }

    /**
     * {@code hot spans:} and one indented line per summary, then {@code breakdown:}
     * on a line of its own: where the time went over the finding's sample (agent.md,
     * "Where the time went").
     *
     * <p>Lines rather than a table, because there are three of them and they sit
     * inside a finding's block; the indent is the one the block already uses, so the
     * continuation lines read as continuations.
     *
     * <p>Nothing is appended when the finding has neither.
     */
    private static void whereTheTimeWent(StringBuilder text, Map<String, Object> numbers) {
        if (numbers.get(HOT_SPANS) instanceof List<?> spans && !spans.isEmpty()) {
            String indent = "";
            text.append("   hot spans: ");
            for (Object each : spans) {
                if (!(each instanceof Map<?, ?> hot)) {
                    continue;
                }
                text.append(indent).append(hotSpanLine(hot))
                        .append(" · ").append(percent(hot.get("share")))
                        .append(" · ×").append(scalar(hot.get("count"))).append('\n');
                indent = "              ";
            }
        }
        if (numbers.get(BREAKDOWN) instanceof Map<?, ?> breakdown && !breakdown.isEmpty()) {
            List<String> parts = new ArrayList<>();
            breakdown.forEach((bucket, share) -> parts.add(bucket + " " + percent(share)));
            text.append("   breakdown: ").append(String.join(" · ", parts)).append('\n');
        }
    }

    private static String millis(@Nullable Object value) {
        return value instanceof Number number ? Numbers.millis(number.doubleValue()) : "—";
    }

    private static String percent(@Nullable Object value) {
        return value instanceof Number number ? Numbers.percent(number.doubleValue()) : "—";
    }

    /**
     * A number said the way its name means it: an instant is a time, a share is a
     * percentage, an Apdex is a score. Without this a {@code firstSeen} would read
     * as {@code 1,789,596,953,808}, which is a number and not an answer.
     */
    private static String formatByKey(String key, @Nullable Object value) {
        if (value instanceof Number number) {
            if ("at".equals(key) || key.endsWith("Seen") || key.endsWith("At")) {
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

    private static String scalar(@Nullable Object value) {
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
                        map.forEach((key, each) ->
                                inner.add(key + " " + formatByKey(String.valueOf(key), each)));
                        parts.add("[" + String.join(" ", inner) + "]");
                    } else {
                        parts.add(singleLine(String.valueOf(element)));
                    }
                }
                yield String.join(" ", parts);
            }
            case String text -> singleLine(text);
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
            row(text, List.of(instantMillis(mark.at()), mark.name(), orDash(mark.service()), orDash(mark.note())));
        }
        return text.toString();
    }

    /**
     * {@code imported 12,345 spans, 456 logs, 7,890 metric points, 12 tingles,
     * 3 marks (2 traces already present) from 2026-09-18T12:37:06+09:00 → 12:41:08}.
     *
     * <p>One line, because an import is one fact. The window is on it because the
     * rows keep the instants they were exported with, so the next question is
     * always which window to read (agent.md).
     */
    static String imported(Importer.Result result) {
        StringBuilder line = new StringBuilder("imported ")
                .append(Numbers.count(result.spans())).append(" spans, ")
                .append(Numbers.count(result.logs())).append(" logs, ")
                .append(Numbers.count(result.metricPoints())).append(" metric points, ")
                .append(Numbers.count(result.tingles())).append(" tingles, ")
                .append(Numbers.count(result.marks())).append(" marks");
        if (result.skippedTraces() > 0) {
            line.append(" (").append(Numbers.plural(result.skippedTraces(), "trace"))
                    .append(" already present)");
        }
        return line.append(" from ").append(interval(result.from(), result.to())).append('\n').toString();
    }

    static String mark(Marks.Mark mark) {
        return "mark " + mark.name() + " at " + instantMillis(mark.at())
                + (mark.service() == null ? "" : " (" + mark.service() + ")")
                + (mark.note() == null ? "" : " — " + escapedLine(mark.note())) + "\n";
    }

    // --- acknowledgements -----------------------------------------------------

    /** One line, as a mark's is: what happened, to which finding, and why. */
    static String ack(Acks.Ack ack) {
        return "acked " + ack.findingId()
                + (ack.note() == null ? "" : " — " + escapedLine(ack.note())) + "\n";
    }

    static String unack(String findingId) {
        return "unacked " + findingId + "\n";
    }

    static String resolve(Acks.Ack resolution) {
        return "resolved " + resolution.findingId()
                + (resolution.note() == null ? "" : " — " + escapedLine(resolution.note())) + "\n";
    }

    static String unresolve(String findingId) {
        return "unresolved " + findingId + "\n";
    }

    static String acks(List<Acks.Ack> acks) {
        if (acks.isEmpty()) {
            return "# acks\n\nno acknowledged findings;"
                    + " POST /api/findings/{id}/ack or `ack <finding id>` records one\n";
        }
        StringBuilder text = new StringBuilder("# acks\n\n");
        table(text, List.of("at", "finding", "note"));
        for (Acks.Ack ack : acks) {
            row(text, List.of(instantMillis(ack.at()), ack.findingId(), orDash(ack.note())));
        }
        return text.toString();
    }

    // --- compare --------------------------------------------------------------

    static String compare(Compare.Comparison comparison, @Nullable String service,
            boolean full) {
        StringBuilder text = new StringBuilder("# compare  ")
                .append(interval(comparison.before().from(), comparison.before().to())).append("  vs  ")
                .append(interval(comparison.after().from(), comparison.after().to()))
                .append("  (").append(scope(service)).append(")\n\n");

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
            table(text, COMPARE_ENDPOINT_COLUMNS, comparison.endpoints());
        }

        text.append("\n## queries\n\n");
        if (comparison.queries().isEmpty()) {
            text.append("no query in either window\n");
        } else {
            table(text, compareQueryColumns(full), comparison.queries());
        }

        text.append("\n## errors\n\n");
        if (comparison.errors().isEmpty()) {
            text.append("no error in either window\n");
        } else {
            table(text, List.of("verdict", "id", "type", "message", "before", "after"));
            for (Compare.ErrorDiff diff : comparison.errors()) {
                row(text, List.of(diff.verdict(), diff.errorId(), orDash(diff.type()), orDash(diff.message()),
                        String.valueOf(diff.before()), String.valueOf(diff.after())));
            }
        }
        return text.toString();
    }

    /**
     * {@code 12 → 3}: one number of the two windows in one cell, a dash where a side has nothing
     * or the side is not there at all.
     */
    private static <S, V> String pair(@Nullable S before, @Nullable S after, Function<S, V> field,
            Function<@Nullable V, String> format) {
        return format.apply(before == null ? null : field.apply(before)) + " → "
                + format.apply(after == null ? null : field.apply(after));
    }

    private static String count(@Nullable Long value) {
        return value == null ? "—" : Numbers.count(value);
    }

    private static String millis(@Nullable Double value) {
        return value == null ? "—" : Numbers.millis(value);
    }

    private static String number(@Nullable Double value) {
        return value == null ? "—" : Numbers.number(value);
    }

    // --- check ----------------------------------------------------------------

    static String check(Check.CheckResult result, Window window, @Nullable String service,
            @Nullable String endpoint) {
        StringBuilder text = new StringBuilder(headingOf("check  " + result.verdict().word(), window,
                scope(service) + (endpoint == null ? "" : ", " + endpoint), result.requests(), 0, 0))
                .append('\n');
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

    static String traces(Window window, @Nullable String service, List<Stats.TraceSummary> traces, long total,
            long requests, String otlpEndpoint) {
        if (traces.isEmpty()) {
            return heading("traces", window, service, requests) + "\n"
                    + empty("traces", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("traces", window, service, requests));
        text.append('\n');
        // The table is a page of the newest; the count says how many the window holds.
        text.append(traces.size() < total ? traces.size() + " of " : "").append(plural(total, "trace"))
                .append(", newest first\n\n");
        table(text, TRACE_COLUMNS, traces);
        return text.toString();
    }

    static String endpoints(Window window, @Nullable String service, List<Stats.EndpointStats> endpoints,
            long requests, String otlpEndpoint) {
        if (endpoints.isEmpty()) {
            return heading("endpoints", window, service, requests) + "\n"
                    + empty("endpoints", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("endpoints", window, service, requests));
        text.append('\n');
        table(text, ENDPOINT_COLUMNS, endpoints);
        return text.toString();
    }

    static String queries(Window window, @Nullable String service, List<Stats.QueryStats> queries, long requests,
            boolean full, String otlpEndpoint) {
        if (queries.isEmpty()) {
            return heading("queries", window, service, requests) + "\n"
                    + empty("queries", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("queries", window, service, requests));
        text.append('\n');
        table(text, queryColumns(full), queries);
        return text.toString();
    }

    /** One query group as the list renders it, headed by its id (agent.md, "One finding"). */
    static String query(Window window, @Nullable String service, Stats.QueryStats query, long requests,
            boolean full) {
        StringBuilder text = new StringBuilder(heading("query " + query.queryId(), window, service,
                requests));
        text.append('\n');
        table(text, queryColumns(full), List.of(query));
        return text.toString();
    }

    /** {@code GET /orders ×12; GET /report ×3}: who ran a query, and how often. */
    private static String callers(Stats.QueryStats query) {
        List<String> callers = new ArrayList<>();
        for (Stats.Caller caller : query.callers()) {
            callers.add(caller.endpoint() + " ×" + caller.calls());
        }
        return callers.isEmpty() ? "—" : String.join("; ", callers);
    }

    static String errors(Window window, @Nullable String service, List<Stats.ErrorGroup> errors, long requests,
            boolean full, CodeFrames frames, String otlpEndpoint) {
        if (errors.isEmpty()) {
            return heading("errors", window, service, requests) + "\n"
                    + empty("errors", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("errors", window, service, requests));
        text.append('\n');
        table(text, ERROR_COLUMNS, errors);
        for (Stats.ErrorGroup group : errors) {
            errorFrames(text, group, full, frames);
        }
        return text.toString();
    }

    /** One error group as the list renders it, headed by its id (agent.md, "One finding"). */
    static String error(Window window, @Nullable String service, Stats.ErrorGroup group, long requests,
            boolean full, CodeFrames frames) {
        StringBuilder text = new StringBuilder(heading("error " + group.errorId(), window, service,
                requests));
        text.append('\n');
        table(text, ERROR_COLUMNS, List.of(group));
        errorFrames(text, group, full, frames);
        return text.toString();
    }

    /** {@code GET /orders ×12; GET /report ×3}: where an error group was thrown, and how often. */
    private static String endpointCounts(Stats.ErrorGroup group) {
        List<String> endpoints = new ArrayList<>();
        for (Stats.EndpointCount endpoint : group.endpoints()) {
            endpoints.add(endpoint.name() + " ×" + endpoint.count());
        }
        return endpoints.isEmpty() ? "—" : String.join("; ", endpoints);
    }

    /** The sample's application frames under the table, when there are any or {@code full} was asked. */
    private static void errorFrames(StringBuilder text, Stats.ErrorGroup group, boolean full,
            CodeFrames frames) {
        Stats.ErrorSample sample = group.sample();
        if (sample == null) {
            return;
        }
        List<String> code = frames.ofStacktrace(sample.stacktrace());
        if (code.isEmpty() && !full) {
            return;
        }
        text.append('\n').append(group.errorId()).append(" — trace ")
                .append(sample.traceId()).append('\n');
        for (String frame : code) {
            text.append("   ").append(frame).append('\n');
        }
    }

    /**
     * The log lines, newest first.
     *
     * @param requests the requests of the window, which only the empty answer says: no
     *        log line in a window that has requests is the healthy case, not a missing exporter
     */
    static String logs(Window window, @Nullable String service, List<LogRecord> logs, long total,
            long requests, String otlpEndpoint) {
        if (logs.isEmpty()) {
            return heading("logs", window, service, null) + "\n"
                    + empty("logs", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("logs", window, service, null));
        text.append('\n')
                .append(logs.size() < total ? logs.size() + " of " : "").append(plural(total, "line"))
                .append(", newest first\n\n");
        for (LogRecord log : logs) {
            text.append(logLine(log, true));
        }
        return text.toString();
    }

    /**
     * {@code 10:15:02.123  ERROR  com.acme.Orders  Payment failed  trace 4bf9…}: one log record on
     * one line, its trace id at the end when it has one and the list is not already one trace's.
     */
    private static String logLine(LogRecord log, boolean withTrace) {
        StringBuilder line = new StringBuilder(clockMillis(log.at())).append("  ")
                .append(pad(log.severity(), 6)).append(' ')
                .append(orDash(log.logger())).append("  ")
                .append(escapedLine(log.body()));
        if (withTrace && log.traceId() != null) {
            line.append("  trace ").append(log.traceId());
        }
        return line.append('\n').toString();
    }

    static String services(Window window, List<Stats.ServiceSummary> services, long requests,
            String otlpEndpoint) {
        if (services.isEmpty()) {
            return heading("services", window, null, requests) + "\n"
                    + empty("services", window, requests, otlpEndpoint);
        }
        StringBuilder text = new StringBuilder(heading("services", window, null, requests));
        text.append('\n');
        table(text, SERVICE_COLUMNS, services);
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
            return depth + " " + span.category() + " " + Ids.normaliseDigits(span.summary());
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
                .append(plural(errorCount, "error")).append("\n\n");
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
                text.append(logLine(log, false));
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
            Map<String, List<SpanRecord>> children, int depth, @Nullable String parentService,
            long startNs,
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
                                : List.of(statementLine(first.dbStatement(), full))));
                i += run;
                continue;
            }
            List<String> under = new ArrayList<>();
            if (first.dbStatement() != null && (full || tingles.isSlow(first))) {
                under.add(statementLine(first.dbStatement(), full));
            }
            if (first.isError()) {
                String message = first.errorMessage();
                under.add("exception " + Findings.simpleName(first.errorType())
                        + (message == null || message.isBlank() ? "" : ": " + escapedLine(message)));
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
    private static String spanText(SpanRecord span, @Nullable String parentService) {
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
    record DiffLine(char op, @Nullable TraceLine a, @Nullable TraceLine b, boolean counted) {

        /** The side the line is rendered from: {@code a} where there is one. */
        TraceLine either() {
            // Every line came from one side or the other; neither is a line at all.
            return Objects.requireNonNull(a == null ? b : a, "a diff line has a side");
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
    private static String side(@Nullable TraceLine line) {
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
        table(text, result.columns());
        for (List<Object> row : result.rows()) {
            List<String> cells = new ArrayList<>(row.size());
            for (Object value : row) {
                cells.add(sqlValue(value, full));
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
    private static String sqlValue(@Nullable Object value, boolean full) {
        return switch (value) {
            case null -> "—";
            case Boolean flag -> String.valueOf(flag);
            case Long number -> String.valueOf(number);
            case Double number -> String.valueOf(number);
            default -> cutToStatement(String.valueOf(value), full);
        };
    }

    /** A statement on a line of its own: one line, cut at 200 characters unless {@code full}. */
    static String statementLine(@Nullable String statement, boolean full) {
        return statement == null ? "—" : escapeBars(cutToStatement(statement, full));
    }

    /**
     * A statement or a value cut to one line and to 200 characters unless
     * {@code full}, with its bars left alone: {@link #row} escapes a cell once,
     * after the cut, so the cut never splits an escape.
     */
    private static String cutToStatement(@Nullable String value, boolean full) {
        if (value == null) {
            return "—";
        }
        String single = singleLine(value);
        return full || single.length() <= STATEMENT ? single : single.substring(0, STATEMENT) + "…";
    }

    /** Free text on a line of its own, or inside one: no newline, and no bar a table could split on. */
    static String escapedLine(@Nullable String value) {
        return value == null ? "—" : escapeBars(singleLine(value));
    }

    private static String singleLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String escapeBars(String value) {
        return value.replace("|", "\\|");
    }

    /**
     * A table cell: one line, its bars escaped, and {@code —} when nothing is
     * left. Every cell goes through it, because almost every cell carries text
     * the application wrote — an exception message with its newlines, a note, a
     * span name, a service name — and one newline or bar in it breaks the row.
     */
    private static String tableCell(String value) {
        String single = singleLine(value);
        return single.isEmpty() ? "—" : escapeBars(single);
    }

    private static String orDash(@Nullable String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** A table with a row per element, each cell written by its column. */
    private static <T> void table(StringBuilder text, List<Column<T>> columns, List<T> rows) {
        table(text, columns.stream().map(Column::header).toList());
        for (T each : rows) {
            row(text, columns.stream().map(column -> column.cell().apply(each)).toList());
        }
    }

    private static void table(StringBuilder text, List<String> columns) {
        row(text, columns);
        List<String> rule = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            rule.add("---");
        }
        row(text, rule);
    }

    /** One row of a table, every cell made safe for it by {@link #tableCell(String)}. */
    private static void row(StringBuilder text, List<String> cells) {
        text.append('|');
        for (String each : cells) {
            text.append(' ').append(tableCell(each)).append(" |");
        }
        text.append('\n');
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }
}
