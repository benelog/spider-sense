package net.benelog.spidersense.cli;

/**
 * The table cli.adoc#help prints, and the same table a usage error
 * prints after its one line.
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
              logs [--severity=WARN] [--q=<text>] [--trace=<traceId>]
                                           log lines
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
              init [--dir=<project dir>] [--jar=<path>] [--no-skill] [--mcp]
                                           writes the Spider Sense block into the project's
                                           CLAUDE.md and installs the skills into .claude/skills/;
                                           --mcp also writes the stdio MCP server into .mcp.json
              mcp                          the MCP server over stdio, for a host with no shell;
                                           takes --url and --db and nothing else
              help                         this table

            Common options:
              --since=<selector>   default 15m
              --until=<selector>   default now
              --service=<name>     one service
              --limit=<n>          the lists: findings, traces, queries, errors, logs, marks,
                                   and the rows of sql (default 200, at most 5000)
              --url=<base url>     default http://127.0.0.1:4000, or SPIDERSENSE_URL, or what
                                   spider-sense.properties in the working directory implies
              --db=<path or jdbc url>   read the database directly, without asking any server
              --json               the JSON of the HTTP API instead of the text
              --full               whole statements, every repeated span
              --hide-acked         findings only: leave acknowledged findings out
              --no-git             findings only: no suspect-change line under the code frames

            A selector is a duration (30s, 5m, 2h, 1d), epoch milliseconds, a mark name,
            start (the newest automatic start mark) or now.

            With no --url and nothing listening, the database file is read in process; the
            thresholds are then --slow.request.ms, --slow.query.ms and --app.packages, since
            no server is there to ask.

            Exit codes: 0 success, 1 check failed, 2 usage or connection error,
            3 check had no request to judge, 4 not found (a trace id, a mark name,
            a finding id to unack or unresolve).""";

    private Help() {
    }
}
