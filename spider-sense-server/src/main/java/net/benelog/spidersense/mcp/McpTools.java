package net.benelog.spidersense.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.cli.Limits;
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
 * same switch as {@code cli/Local.answer}: the same defaults, the same caps, the
 * same window resolution through {@link Selectors}, so an MCP answer and a CLI
 * answer over the same window are the same bytes (mcp.adoc).
 *
 * <p>What the CLI reports with exit code 4 or as a 400 — no such trace, no such
 * mark, a bad selector, a refused statement, a store with no read-only user yet —
 * is a tool result with {@code isError} rather than a protocol error, because it
 * is an answer the model has to act on and not a mistake in the call.
 */
public final class McpTools implements McpServer.ToolRunner {

    private final Reports reports;

    public McpTools(Reports reports) {
        this.reports = reports;
    }

    @Override
    public McpServer.ToolResult call(String name, Map<String, Object> arguments) {
        try {
            return answer(name, arguments);
        } catch (Reports.NoSuchTrace | Selectors.UnknownMark | Selectors.BadSelector
                | Database.ReaderUnavailable | IllegalArgumentException e) {
            return McpServer.ToolResult.failed(oneLine(e.getMessage()));
        }
    }

    private McpServer.ToolResult answer(String name, Map<String, Object> arguments) {
        String service = string(arguments, "service");
        return switch (name) {
            case "findings" -> text(reports.findings(window(arguments, service), service,
                    limit(arguments, Limits.FINDINGS, Limits.FINDINGS_MAX), flag(arguments, "full"),
                    flag(arguments, "hideAcked")));
            case "trace" -> trace(arguments);
            case "mark" -> mark(arguments, service);
            case "resolve" -> text(reports.resolve(
                    reports.resolve(string(arguments, "findingId"), string(arguments, "note"))));
            case "compare" -> compare(arguments, service);
            case "check" -> check(arguments, service);
            case "sql" -> text(reports.sql(string(arguments, "sql"),
                    limit(arguments, Limits.SQL, Limits.SQL_MAX), false));
            default -> McpServer.ToolResult.failed("No such tool: " + name);
        };
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

    private McpServer.ToolResult mark(Map<String, Object> arguments, @Nullable String service) {
        Marks.Mark mark = reports.mark(string(arguments, "name"), string(arguments, "note"), service);
        return text(reports.mark(mark));
    }

    /**
     * The two windows, resolved the way {@code /api/compare} and the CLI resolve
     * them: the end first, then {@code after} counted back from it, then
     * {@code before} counted back from that.
     */
    private McpServer.ToolResult compare(Map<String, Object> arguments, @Nullable String service) {
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
    private McpServer.ToolResult check(Map<String, Object> arguments, @Nullable String service) {
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
        return reports.selectors().window(null, null, since == null ? Limits.SINCE : since,
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
        Object value = arguments.get("limit");
        if (!(value instanceof Number number)) {
            return fallback;
        }
        return Math.min(Math.max(1, number.intValue()), max);
    }

    /** A tool result is one message, and a host shows it as one line. */
    private static String oneLine(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "the call could not be answered";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
