package net.benelog.spidersense.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The table cli.adoc#help prints, and the block of one command that {@code help <command>} and
 * {@code <command> --help} print.
 *
 * <p>Plain ASCII on purpose: this is the one output of the CLI that is written
 * here rather than rendered by the server, and a console that cannot show an
 * ellipsis should still be able to show the help.
 */
final class Help {

    static final String TEXT = """
            Spider Sense: ask a running Spider Sense, or the database file, from the terminal.

              java -jar spider-sense.jar <command> [arguments] [options]

            Commands:
              status                       what is running, where the database is, how much it holds
              findings [--hide-acked] [--no-git]
                                           the findings of the window; under each code frame,
                                           the suspect change git names (uncommitted, or the
                                           commit that last changed the line)
              ack <finding id> [--note=<text>]
                                           accepts a known finding, so it is ranked last
              unack <finding id>           withdraws that acknowledgement
              resolve <finding id> [--note=<text>]
                                           marks a finding fixed; if it comes back it is a
                                           regression, ranked first
              unresolve <finding id>       withdraws that resolution
              trace <traceId> [--full] [--diff=<traceId>]
                                           one trace as a tree, or two aligned
              tail [--kind=slow-request|slow-query|error] [--service=<name>]
                   [--until-traces=<n>] [--timeout=<duration>]
                                           tingles as they arrive, one line each
              traces [--status=error|ok] [--min-ms=<n>] [--q=<text>] [--limit=20]
                                           the newest traces
              endpoints                    the endpoints of the window
              queries                      the database statements of the window
              errors                       the errors of the window
              logs [--severity=<level>] [--q=<text>] [--trace=<traceId>]
                                           log lines; --severity is the lowest level shown:
                                           TRACE, DEBUG, INFO, WARN, ERROR or FATAL
              mark <name> [--note=<text>]  records a mark now
              marks                        lists marks
              compare --before=<selector> --after=<selector> [--until=<selector>]
                                           the two windows side by side
              check [--max-p95-ms=] [--max-errors=] [--max-error-rate=]
                    [--max-queries-per-request=] [--max-slow-queries=]
                    [--max-n-plus-one=] [--max-log-errors=] [--max-regressions=]
                    [--min-apdex=] [--endpoint=]
                                           pass or fail, in the exit code
              sql "<statement>" [--limit=200]
                                           read-only SQL over the store (SELECT only)
              export [--out=<file>]        the window as one JSON document, to the file or to
                                           stdout; a name ending in .gz is gzipped
              import <file>                that document back into the store, and one line
                                           saying what arrived
              init [--dir=<project dir>] [--jar=<path>] [--url=<base url>] [--gradle]
                   [--no-skill] [--mcp]
                                           writes the Spider Sense block into the project's
                                           CLAUDE.md or AGENTS.md and installs the skills into
                                           .claude/skills/; --mcp writes the stdio MCP server
                                           into .mcp.json instead of the skills
              mcp                          the MCP server over stdio, for a host with no shell;
                                           takes --url and --db and nothing else
              help [<command>]             this table, or what one command takes, which
                                           <command> --help prints too
              version                      the version of this jar, as --version prints it

            Common options:
              --since=<selector>   default 15m
              --until=<selector>   default now
              --service=<name>     one service
              --limit=<n>          how many rows, at least 1; the default and the cap:
                                   findings 20/100, traces 20/1000, queries 100/1000,
                                   errors 100/1000, logs 200/5000, marks 50/500,
                                   sql 200/5000
              --url=<base url>     default http://127.0.0.1:4000, or SPIDERSENSE_URL, or what
                                   spider-sense.properties in the working directory implies
              --db=<path or jdbc url>   read the database directly, without asking any server
              --json               the JSON of the HTTP API instead of the text
              --full               whole statements, every repeated span
              --hide-acked         findings only: leave acknowledged findings out
              --no-git             findings only: no suspect-change line under the code frames

            A selector is a duration (30s, 5m, 2h, 1d), a date-time (2026-10-08T05:50:00,
            with or without an offset), epoch milliseconds, a mark name, start (the newest
            automatic start mark) or now.

            With no --url and nothing listening, the database file is read in process; the
            thresholds are then --slow.request.ms, --slow.query.ms and --app.packages, since
            no server is there to ask.

            Exit codes: 0 success, 1 check failed, 2 usage or connection error,
            3 check had no request to judge, 4 not found (a trace id, a mark name,
            a finding id to unack or unresolve).""";

    /** How a value-taking option is written in a command's block, by what it takes. */
    private static final Map<String, String> VALUES = Map.ofEntries(
            Map.entry("since", "<selector>"), Map.entry("until", "<selector>"),
            Map.entry("before", "<selector>"), Map.entry("after", "<selector>"),
            Map.entry("limit", "<n>"), Map.entry("min-ms", "<n>"), Map.entry("until-traces", "<n>"),
            Map.entry("timeout", "<duration>"), Map.entry("url", "<base url>"),
            Map.entry("db", "<path or jdbc url>"), Map.entry("service", "<name>"),
            Map.entry("note", "<text>"), Map.entry("q", "<text>"),
            Map.entry("trace", "<traceId>"), Map.entry("diff", "<traceId>"),
            Map.entry("status", "error|ok"), Map.entry("severity", "<level>"),
            Map.entry("kind", "<kind>"), Map.entry("endpoint", "<endpoint>"), Map.entry("out", "<file>"),
            Map.entry("dir", "<project dir>"), Map.entry("jar", "<path>"),
            Map.entry("slow.request.ms", "<ms>"), Map.entry("slow.query.ms", "<ms>"),
            Map.entry("app.packages", "<packages>"));

    private static final int WIDTH = 80;

    private Help() {
    }

    /**
     * One command's block: its entry of {@link #TEXT}, then every option its row of
     * {@link Command} takes, the command's own first and the common ones after them.
     *
     * <p>The entry is cut from the table rather than written again, so the two cannot disagree,
     * and the options are the row's, so the block lists exactly what the parser accepts.
     */
    static String of(Command command) {
        List<String> own = new ArrayList<>();
        List<String> common = new ArrayList<>();
        for (String option : command.options()) {
            (Options.isCommon(option) ? common : own).add(spelled(option));
        }
        StringBuilder block = new StringBuilder(entry(command.commandName()));
        if (!own.isEmpty() || !common.isEmpty()) {
            block.append('\n');
        }
        if (!own.isEmpty()) {
            block.append('\n').append(wrapped("Options: ", own));
        }
        if (!common.isEmpty()) {
            block.append('\n').append(wrapped("Common options: ", common));
        }
        return block.append("\n\njava -jar spider-sense.jar help lists every command, the selector forms and the")
                .append("\nexit codes.")
                .toString();
    }

    /** A command's lines of the table, without the table's indent. */
    private static String entry(String name) {
        List<String> lines = new ArrayList<>();
        boolean in = false;
        for (String line : TEXT.lines().dropWhile(line -> !line.equals("Commands:")).skip(1)
                .takeWhile(line -> !line.isEmpty()).toList()) {
            boolean starts = !line.startsWith("   ");
            if (starts) {
                in = line.trim().split("[ \\[]", 2)[0].equals(name);
            }
            if (in) {
                lines.add(line.substring(2));
            }
        }
        return String.join("\n", lines);
    }

    private static String spelled(String option) {
        if (Options.isFlag(option)) {
            return "--" + option;
        }
        // The rules of check are the rest: a count, a rate, a score or milliseconds, each a number.
        return "--" + option + "=" + VALUES.getOrDefault(option, "<number>");
    }

    /** The options after the label, as many to a line as fit in {@link #WIDTH}, the rest indented under. */
    private static String wrapped(String label, List<String> options) {
        StringBuilder out = new StringBuilder(label);
        String indent = " ".repeat(label.length());
        int column = label.length();
        boolean first = true;
        for (String option : options) {
            if (!first && column + 1 + option.length() > WIDTH) {
                out.append('\n').append(indent);
                column = indent.length();
                first = true;
            }
            if (!first) {
                out.append(' ');
                column++;
            }
            out.append(option);
            column += option.length();
            first = false;
        }
        return out.toString();
    }
}
