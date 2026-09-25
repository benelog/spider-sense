package net.benelog.spidersense.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.benelog.spidersense.api.Limits;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The seven tools, as the seven {@link Reports} calls the CLI makes.
 *
 * <p>This is the whole of the MCP adapter's behaviour, and it is deliberately the
 * same calls as {@code cli/Local.answer}: the same defaults, the same caps, the
 * same window resolution through {@link Selectors}, so an MCP answer and a CLI
 * answer over the same window are the same bytes (mcp.adoc).
 *
 * <p>What the CLI reports with exit code 4 or as a 400 — no such trace, no such
 * mark, a bad selector, a refused statement, a store with no read-only user yet —
 * is a tool result with {@code isError} rather than a protocol error, because it
 * is an answer the model has to act on and not a mistake in the call.
 */
public final class McpTools implements McpServer.ToolRunner {

    private static final Tool.Arg SINCE = Tool.Arg.string("since",
            "Start of the window: a duration (30s, 5m, 2h, 1d), a mark name, "
                    + "start (since the application was last restarted), now, or epoch "
                    + "milliseconds. Defaults to " + Selectors.DEFAULT_SINCE + ".");
    private static final Tool.Arg UNTIL = Tool.Arg.string("until",
            "End of the window, in the same forms as since. Defaults to now.");
    private static final Tool.Arg SERVICE = Tool.Arg.string("service",
            "Narrow the answer to one service by name; every service by default.");

    /**
     * The seven tools of mcp.adoc#tools, their schemas, their descriptions and their handlers.
     *
     * <p>The check rules come from {@link Check#RULES} and the list caps from {@link Limits}, so a
     * new rule or a changed cap reaches MCP without anyone writing it here.
     */
    public static final List<Tool> TOOLS = List.of(
            new Tool("findings",
                    "Use this first, and after every change: the ranked list of what is worth "
                            + "fixing in a time window — N+1 queries, slow queries, slow endpoints, "
                            + "slow jobs, errors and exhausted connection pools — each with the "
                            + "numbers that justify it and the trace ids that prove it.",
                    List.of(SINCE, UNTIL, SERVICE,
                            Tool.Arg.integer("limit", "How many findings to return; "
                                    + Limits.FINDINGS + " by default.", 1, Limits.FINDINGS_MAX),
                            Tool.Arg.bool("full", "Keep statements whole instead of cutting them at "
                                    + Limits.STATEMENT_CHARS + " characters."),
                            Tool.Arg.bool("hideAcked", "Leave out the findings that have been "
                                    + "acknowledged; they are ranked last otherwise.")),
                    List.of(), McpTools::findings),
            new Tool("trace",
                    "Use this to open the evidence behind a finding: one request as an indented "
                            + "tree of spans, with the statement under each database span, the "
                            + "exception and application frames under each error, and the trace's "
                            + "log lines.",
                    List.of(Tool.Arg.string("traceId",
                                    "The 32 hex characters a finding or a trace list named."),
                            Tool.Arg.bool("full", "Expand the repeated spans a tree collapses into "
                                    + "one row, and keep statements whole."),
                            Tool.Arg.string("diff", "A second trace id: the two trees are aligned "
                                    + "and answered as one text, which is how the same request "
                                    + "before and after a change is read.")),
                    List.of("traceId"), McpTools::trace),
            new Tool("mark",
                    "Use this to name the moment before you exercise the application, so a later "
                            + "compare can measure what a change did; marks are also what since "
                            + "accepts in place of a duration.",
                    List.of(Tool.Arg.string("name", "The mark's name, such as before or after-fix.",
                                    "^[A-Za-z0-9._-]{1,64}$"),
                            Tool.Arg.string("note", "A sentence recorded with the moment."),
                            Tool.Arg.string("service", "The service the moment belongs to.")),
                    List.of("name"), McpTools::mark),
            new Tool("resolve",
                    "Use this after a fix is confirmed: it records that the finding is fixed, "
                            + "so if it ever comes back it is reported as a regression, ranked "
                            + "above everything else.",
                    List.of(Tool.Arg.string("findingId",
                                    "The finding id a findings answer printed, such as "
                                            + "n-plus-one:1a2b3c4d5e6f."),
                            Tool.Arg.string("note", "A sentence recorded with the resolution, such "
                                    + "as what the fix was.")),
                    List.of("findingId"), McpTools::resolve),
            new Tool("compare",
                    "Use this after a change to answer whether it helped: the window between two "
                            + "marks placed beside the window after the second, endpoint by "
                            + "endpoint and query by query, with a verdict on every row.",
                    List.of(Tool.Arg.string("before", "Selector where the first window starts, "
                                    + "usually the mark taken before the change."),
                            Tool.Arg.string("after", "Selector where the first window ends and the "
                                    + "second begins, usually the mark taken after it."),
                            Tool.Arg.string("until", "Selector where the second window ends; now by "
                                    + "default."),
                            SERVICE),
                    List.of("before", "after"), McpTools::compare),
            new Tool("check",
                    "Use this to decide whether a fix is done: the thresholds you name turned "
                            + "into one pass or fail verdict over the window, the way a test is "
                            + "used; with no rule the defaults are maxErrors 0, maxNPlusOne 0, "
                            + "maxRegressions 0 and maxP95Ms the slow-request threshold.",
                    checkArgs(), List.of(), McpTools::check),
            new Tool("sql",
                    "Use this only when no other tool has a column for the question: one "
                            + "read-only statement over Spider Sense's own H2 schema, which "
                            + "the Storage chapter of the manual documents.",
                    List.of(Tool.Arg.string("sql",
                                    "One statement starting with SELECT, WITH, TABLE, VALUES, "
                                            + "EXPLAIN or SHOW; nothing that writes is allowed."),
                            Tool.Arg.integer("limit", "How many rows to return; "
                                    + Limits.SQL + " by default.", 1, Limits.SQL_MAX)),
                    List.of("sql"), McpTools::sql));

    /** The window and scope of {@code check}, then one number per rule of {@link Check#RULES}. */
    private static List<Tool.Arg> checkArgs() {
        List<Tool.Arg> args = new ArrayList<>(List.of(SINCE, UNTIL, SERVICE,
                Tool.Arg.string("endpoint", "Narrow the verdict to one endpoint, by id or "
                        + "by name (GET /orders/{id}).")));
        for (String rule : Check.RULES) {
            args.add(Tool.Arg.number(rule, Check.describe(rule)));
        }
        return List.copyOf(args);
    }

    private final Reports reports;

    public McpTools(Reports reports) {
        this.reports = reports;
    }

    @Override
    public McpServer.ToolResult call(String name, Map<String, Object> arguments) {
        Tool tool = TOOLS.stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElse(null);
        if (tool == null) {
            return McpServer.ToolResult.failed("No such tool: " + name);
        }
        try {
            return tool.handler().answer(this, arguments);
        } catch (Reports.NoSuchTrace | Selectors.UnknownMark | Selectors.BadSelector
                | Database.ReaderUnavailable | IllegalArgumentException e) {
            return McpServer.ToolResult.failed(oneLine(e.getMessage()));
        }
    }

    private McpServer.ToolResult findings(Map<String, Object> arguments) {
        String service = string(arguments, "service");
        return text(reports.findings(window(arguments, service), service,
                limit(arguments, Limits.FINDINGS, Limits.FINDINGS_MAX), flag(arguments, "full"),
                flag(arguments, "hideAcked")));
    }

    private McpServer.ToolResult resolve(Map<String, Object> arguments) {
        return text(reports.resolve(
                reports.resolve(string(arguments, "findingId"), string(arguments, "note"))));
    }

    private McpServer.ToolResult sql(Map<String, Object> arguments) {
        return text(reports.sql(string(arguments, "sql"),
                limit(arguments, Limits.SQL, Limits.SQL_MAX), false));
    }

    private McpServer.ToolResult trace(Map<String, Object> arguments) {
        String traceId = string(arguments, "traceId");
        if (traceId == null) {
            // The tool's schema names traceId as required; a host that sent none
            // gets the tool's own failure rather than a protocol error.
            return McpServer.ToolResult.failed("trace needs a traceId");
        }
        String diff = string(arguments, "diff");
        if (diff != null) {
            return text(reports.traceDiff(traceId, diff, flag(arguments, "full")));
        }
        Reports.Report report = reports.trace(traceId, flag(arguments, "full"));
        return report == null
                ? McpServer.ToolResult.failed("No such trace: " + traceId)
                : text(report);
    }

    private McpServer.ToolResult mark(Map<String, Object> arguments) {
        String service = string(arguments, "service");
        Marks.Mark mark = reports.mark(string(arguments, "name"), string(arguments, "note"), service);
        return text(reports.mark(mark));
    }

    /**
     * The two windows, resolved the way {@code /api/compare} and the CLI resolve
     * them: the end first, then {@code after} counted back from it, then
     * {@code before} counted back from that.
     */
    private McpServer.ToolResult compare(Map<String, Object> arguments) {
        String service = string(arguments, "service");
        Selectors selectors = reports.selectors();
        long now = System.currentTimeMillis();
        String until = string(arguments, "until");
        long untilAt = until == null ? now : selectors.resolve(until, now, service);
        long afterAt = selectors.resolve(string(arguments, "after"), untilAt, service);
        long beforeAt = selectors.resolve(string(arguments, "before"), afterAt, service);
        return text(reports.compare(beforeAt, afterAt, untilAt, service, false));
    }

    /**
     * The verdict twice: in the text for a reader, and in
     * {@code structuredContent} so a host need not read the heading for it
     * (mcp.adoc#tools).
     */
    private McpServer.ToolResult check(Map<String, Object> arguments) {
        String service = string(arguments, "service");
        Map<String, Double> rules = new LinkedHashMap<>();
        for (String rule : Check.RULES) {
            Object value = arguments.get(rule);
            if (value instanceof Number number) {
                if (!Double.isFinite(number.doubleValue())) {
                    throw new McpServer.BadArgument(rule + " must be a finite number");
                }
                rules.put(rule, number.doubleValue());
            }
        }
        Reports.Report report = reports.check(window(arguments, service), service,
                string(arguments, "endpoint"), rules);
        Json.JsonObject json = report.json().asObject();
        Json.JsonValue pass = json.get("pass");
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("pass", pass.isNull() ? null : pass.asBoolean());
        structured.put("requests", json.getLong("requests"));
        return new McpServer.ToolResult(report.text(), false, structured);
    }

    private Window window(Map<String, Object> arguments, @Nullable String service) {
        String since = string(arguments, "since");
        return reports.selectors().window(null, null, since == null ? Selectors.DEFAULT_SINCE : since,
                string(arguments, "until"), service);
    }

    private static McpServer.ToolResult text(Reports.Report report) {
        return McpServer.ToolResult.of(report.text());
    }

    private static @Nullable String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String said ? said : null;
    }

    private static boolean flag(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof Boolean set && set;
    }

    /** Clamped the way the handlers and the CLI clamp it, so the lists agree. */
    private static int limit(Map<String, Object> arguments, int fallback, int max) {
        return Limits.clamp(arguments.get("limit") instanceof Number number ? number.intValue() : null,
                fallback, max);
    }

    /** A tool result is one message, and a host shows it as one line. */
    private static String oneLine(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "the call could not be answered";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
