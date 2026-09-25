package net.benelog.spidersense.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.benelog.spidersense.api.Limits;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * Every command of the CLI, one row each of {@link #ALL}, in the order {@link Help} lists them: its name, the
 * options it takes, the word of its own it takes, and how each mode answers it.
 *
 * <p>This is the one place a command is described. {@link Options#parse} checks a command line
 * against the row, {@link Remote} asks the server the row's method and path with its body, and
 * {@link Local} answers from the row's {@link Reports} call. A flag wired into one mode and not the
 * other would print different rows depending on whether a server happens to be running, which is
 * why the two halves of a row sit side by side; {@code CliParityTest} runs every row both ways.
 *
 * <p>A row with no path and no local answer is a command {@link Cli} dispatches before either
 * mode ({@code init}, {@code mcp}, {@code tail}, {@code help}), or one each mode answers in its
 * own way beside the row ({@code export}, {@code import}'s upload, {@code check}'s verdict, and the
 * withdrawal of {@code unack} and {@code unresolve}).
 *
 * @param commandName what the command line calls it
 * @param options     the options it takes, without the {@code --}
 * @param argument    the word of its own it takes, as a usage error names it; null for none
 * @param method      how {@link Remote} sends it; null for a command it does not send this way
 * @param path        the path and the command's own query parameters, before {@code full} and
 *                    {@code format}; null with the method
 * @param body        the JSON body of a {@code POST}; null for none
 * @param local       the answer from the file; null for a command {@link Local} answers another way
 */
record Command(String commandName, Set<String> options, @Nullable String argument,
        Command.@Nullable Method method, @Nullable Function<Options, Remote.Query> path,
        @Nullable Function<Options, String> body, Command.@Nullable LocalAnswer local) {

    /** Every row, in the order {@link Help} lists them. */
    static final List<Command> ALL = List.of(
            new Command(Options.STATUS, with(), null, Method.GET,
                options -> new Remote.Query("/api/status"), null,
                (options, reports, service) -> reports.status("file", null, 0)),

            new Command(Options.FINDINGS, with("since", "until", "limit", "full", "hide-acked", "no-git"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/findings"))
                        .add("limit", options.limit(Limits.FINDINGS, Limits.FINDINGS_MAX))
                        .add("hideAcked", options.flag("hide-acked") ? "true" : null),
                null,
                (options, reports, service) -> reports.findings(Local.window(options, reports, service), service,
                        new Reports.FindingsAsk(options.limit(Limits.FINDINGS, Limits.FINDINGS_MAX),
                                options.flag("full"), options.flag("hide-acked")))),

            new Command(Options.ACK, with("note"), "a finding id", Method.POST,
                options -> new Remote.Query("/api/findings/" + Remote.encode(options.requiredArgument()) + "/ack"),
                Command::noteBody,
                (options, reports, service) -> reports.ack(
                        reports.ack(options.requiredArgument(), options.valueOrNull("note")))),

            new Command(Options.UNACK, with(), "a finding id", Method.DELETE,
                options -> new Remote.Query("/api/findings/" + Remote.encode(options.requiredArgument()) + "/ack"),
                null, null),

            new Command(Options.RESOLVE, with("note"), "a finding id", Method.POST,
                options -> new Remote.Query("/api/findings/" + Remote.encode(options.requiredArgument()) + "/resolve"),
                Command::noteBody,
                (options, reports, service) -> reports.resolve(
                        reports.resolve(options.requiredArgument(), options.valueOrNull("note")))),

            new Command(Options.UNRESOLVE, with(), "a finding id", Method.DELETE,
                options -> new Remote.Query("/api/findings/" + Remote.encode(options.requiredArgument()) + "/resolve"),
                null, null),

            new Command(Options.TRACE, with("full", "diff"), "a trace id", Method.GET,
                options -> new Remote.Query("/api/traces/" + Remote.encode(options.requiredArgument()))
                        .add("diff", options.valueOrNull("diff")),
                null,
                (options, reports, service) -> options.has("diff")
                        ? reports.traceDiff(options.requiredArgument(), options.value("diff", ""),
                                options.flag("full"))
                        : reports.trace(options.requiredArgument(), options.flag("full"))),

            new Command(Options.TAIL, Set.of("url", "json", "service", "kind", "until-traces", "timeout"), null, null,
                null, null, null),

            new Command(Options.TRACES, with("since", "until", "limit", "full", "status", "min-ms", "q"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/traces"))
                        .add("status", options.valueOrNull("status"))
                        .add("minMs", options.valueOrNull("min-ms"))
                        .add("q", options.valueOrNull("q"))
                        .add("limit", options.limit(Limits.CLI_TRACES, Limits.TRACES_MAX)),
                null,
                (options, reports, service) -> reports.traces(new Queries.TraceFilter(
                        Local.window(options, reports, service), service, null,
                        options.optionalLong("min-ms"), null,
                        options.valueOrNull("status"), options.valueOrNull("q"), null,
                        options.limit(Limits.CLI_TRACES, Limits.TRACES_MAX)), options.flag("full"))),

            new Command(Options.ENDPOINTS, with("since", "until"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/endpoints")),
                null,
                (options, reports, service) -> reports.endpoints(Local.window(options, reports, service), service)),

            new Command(Options.QUERIES, with("since", "until", "limit", "full"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/queries"))
                        .add("limit", options.limit(Limits.QUERIES, Limits.QUERIES_MAX)),
                null,
                (options, reports, service) -> reports.queries(Local.window(options, reports, service), service, null,
                        options.limit(Limits.QUERIES, Limits.QUERIES_MAX), options.flag("full"))),

            new Command(Options.ERRORS, with("since", "until", "limit", "full"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/errors"))
                        .add("limit", options.limit(Limits.ERRORS, Limits.ERRORS_MAX)),
                null,
                (options, reports, service) -> reports.errors(Local.window(options, reports, service), service,
                        options.limit(Limits.ERRORS, Limits.ERRORS_MAX), options.flag("full"))),

            new Command(Options.LOGS, with("since", "until", "limit", "severity", "q", "trace"), null, Method.GET,
                options -> Remote.window(options, new Remote.Query("/api/logs"))
                        .add("severity", options.valueOrNull("severity"))
                        .add("q", options.valueOrNull("q"))
                        .add("traceId", options.valueOrNull("trace"))
                        .add("limit", options.limit(Limits.LOGS, Limits.LOGS_MAX)),
                null,
                (options, reports, service) -> reports.logs(new Queries.LogFilter(
                        Local.window(options, reports, service), service,
                        options.valueOrNull("severity"), options.valueOrNull("q"),
                        options.valueOrNull("trace"), null,
                        options.limit(Limits.LOGS, Limits.LOGS_MAX)))),

            new Command(Options.MARK, with("note"), "a mark name", Method.POST,
                options -> new Remote.Query("/api/marks"),
                options -> Json.obj()
                        .put("name", options.requiredArgument())
                        .put("note", options.valueOrNull("note"))
                        .put("service", options.valueOrNull("service"))
                        .toJson(),
                (options, reports, service) -> reports.mark(
                        reports.mark(options.requiredArgument(), options.valueOrNull("note"), service))),

            new Command(Options.MARKS, with("limit"), null, Method.GET,
                options -> new Remote.Query("/api/marks").add("limit", options.limit(Limits.MARKS, Limits.MARKS_MAX)),
                null,
                (options, reports, service) -> reports.marks(options.limit(Limits.MARKS, Limits.MARKS_MAX))),

            new Command(Options.COMPARE, with("before", "after", "until", "full"), null, Method.GET,
                options -> new Remote.Query("/api/compare")
                        .add("before", options.valueOrNull("before"))
                        .add("after", options.valueOrNull("after"))
                        .add("until", options.valueOrNull("until"))
                        .add("service", options.valueOrNull("service")),
                null,
                (options, reports, service) -> reports.compare(options.valueOrNull("before"),
                        options.valueOrNull("after"), options.valueOrNull("until"), service, options.flag("full"))),

            new Command(Options.CHECK, with(Options.checkFlags()), null, Method.GET,
                Remote::check, null, null),

            new Command(Options.SQL, with("limit", "full"), "a statement", Method.POST,
                options -> new Remote.Query("/api/sql"),
                options -> Json.obj()
                        .put("sql", options.requiredArgument())
                        .put("limit", options.limit(Limits.SQL, Limits.SQL_MAX))
                        .toJson(),
                (options, reports, service) -> reports.sql(options.requiredArgument(),
                        options.limit(Limits.SQL, Limits.SQL_MAX), options.flag("full"))),

            new Command(Options.EXPORT, with("since", "until", "out"), null, null, null, null, null),

            // import names a file and a store to write it into; a window and a service belong to the
            // export that made it, not to reading it back.
            new Command(Options.IMPORT, Set.of("url", "db", "json"), "a file to read", null, null, null,
                (options, reports, service) -> reports.imported(reports.importDocument(
                        Json.parse(Sessions.read(options.requiredArgument())).asObject()))),

            // init reads nothing, so none of the common options mean anything to it: --url, --db and the
            // thresholds are all about a window it never opens.
            new Command(Options.INIT, Set.of("dir", "jar", "no-skill", "mcp"), null, null, null, null, null),

            // mcp is not one question but a session of them, so a window, a format and a service belong
            // to each message rather than to the command: only where to read is decided here
            // (mcp.adoc#stdio).
            new Command(Options.MCP, Set.of("url", "db"), null, null, null, null, null),

            new Command(Options.HELP, with(), null, null, null, null, null));

    /** How {@link Remote} sends a command's request. */
    enum Method { GET, POST, DELETE }

    /** A command answered from the file: the report, or null for something named that is not there. */
    @FunctionalInterface
    interface LocalAnswer {
        Reports.@Nullable Report answer(Options options, Reports reports, @Nullable String service);
    }

    /** The command's path and its own parameters, or a usage error for one that is not sent. */
    Remote.Query pathOf(Options given) {
        if (path == null) {
            throw new Options.Usage("unknown command: " + commandName);
        }
        return path.apply(given);
    }

    /** The JSON body, or null for a request that has none. */
    @Nullable String bodyOf(Options given) {
        return body == null ? null : body.apply(given);
    }

    /** The answer from the file, or a usage error for a command that has none. */
    Reports.@Nullable Report answer(Options given, Reports reports, @Nullable String service) {
        if (local == null) {
            throw new Options.Usage("unknown command: " + commandName);
        }
        return local.answer(given, reports, service);
    }

    /** The row of a command name; null for a name that is not a command. */
    static @Nullable Command named(String name) {
        for (Command command : ALL) {
            if (command.commandName.equals(name)) {
                return command;
            }
        }
        return null;
    }

    /** The names, in the order of the table. */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Command command : ALL) {
            names.add(command.commandName);
        }
        return names;
    }

    private static Set<String> with(String... names) {
        return Options.with(names);
    }

    /** The body of {@code ack} and {@code resolve}: the note, when one was given. */
    private static String noteBody(Options options) {
        return Json.obj().put("note", options.valueOrNull("note")).toJson();
    }
}
