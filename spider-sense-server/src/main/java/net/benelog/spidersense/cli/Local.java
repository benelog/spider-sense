package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersilk.json.Json;

/**
 * The CLI when nothing answers at the URL: the H2 file, opened in process for
 * the length of one command.
 *
 * <p>This is what {@code AUTO_SERVER=TRUE} buys — the application has crashed,
 * the UI went with it, and {@code findings --since=start} still answers
 * (agent.md). Every command works here, including {@code mark}, because a mark
 * is a row and not a message to a server.
 *
 * <p>No answer is computed here: {@link Reports} is the seam the HTTP handlers
 * go through too, so the bytes this prints are the bytes the server would have
 * printed.
 */
final class Local {

    private Local() {
    }

    /**
     * The configuration a command line means.
     *
     * <p>Only what was given is passed on, so {@code Config}'s own defaults and
     * the {@code spidersense.*} system properties still decide the rest: there is
     * no server to ask, and the file is the one the server would have opened.
     */
    static Config config(Options options) {
        List<String> args = new ArrayList<>();
        for (String key : List.of("db", "slow.request.ms", "slow.query.ms", "app.packages")) {
            if (options.has(key)) {
                args.add("--" + key + "=" + options.value(key, ""));
            }
        }
        return Config.parse(args.toArray(new String[0]));
    }

    /** What the fallback line on stderr names: the {@code .mv.db}, or the URL of a memory database. */
    static String describe(Config config) {
        Path file = config.databaseFile();
        return file == null ? config.jdbcUrl() : file.toString();
    }

    static int run(Options options, PrintStream out, PrintStream err) {
        return run(options, config(options), out, err);
    }

    static int run(Options options, Config config, PrintStream out, PrintStream err) {
        try (Reports reports = Reports.readOnly(config)) {
            return answer(options, reports, out, err);
        }
    }

    private static int answer(Options options, Reports reports, PrintStream out, PrintStream err) {
        String service = options.value("service", null);
        if (Options.EXPORT.equals(options.command())) {
            return export(options, reports, service, out, err);
        }
        Reports.Report report = switch (options.command()) {
            case "status" -> reports.status("file", null, 0);
            case "findings" -> reports.findings(window(options, reports, service), service,
                    options.limit(Limits.FINDINGS, Limits.FINDINGS_MAX), options.flag("full"));
            case Options.TRACE -> reports.trace(options.argument(), options.flag("full"));
            case "traces" -> reports.traces(new Queries.TraceFilter(
                    window(options, reports, service), service, null,
                    options.optionalLong("min-ms"), null,
                    options.value("status", null), options.value("q", null), null,
                    options.limit(Limits.TRACES, Limits.TRACES_MAX)), options.flag("full"));
            case "endpoints" -> reports.endpoints(window(options, reports, service), service);
            case "queries" -> reports.queries(window(options, reports, service), service, null,
                    options.limit(Limits.QUERIES, Limits.QUERIES_MAX), options.flag("full"));
            case "errors" -> reports.errors(window(options, reports, service), service,
                    options.limit(Limits.ERRORS, Limits.ERRORS_MAX), options.flag("full"));
            case "logs" -> reports.logs(new Queries.LogFilter(
                    window(options, reports, service), service,
                    options.value("severity", null), options.value("q", null),
                    options.value("trace", null), null,
                    options.limit(Limits.LOGS, Limits.LOGS_MAX)));
            case Options.MARK -> mark(options, reports, service);
            case "marks" -> reports.marks(options.limit(Limits.MARKS, Limits.MARKS_MAX));
            case Options.COMPARE -> compare(options, reports, service);
            case Options.SQL -> reports.sql(options.argument(),
                    options.limit(Limits.SQL, Limits.SQL_MAX), options.flag("full"));
            case Options.CHECK -> reports.check(window(options, reports, service), service,
                    options.value("endpoint", null), options.rules());
            case Options.IMPORT -> reports.imported(
                    reports.importDocument(Json.parse(Sessions.read(options.argument())).asObject()));
            default -> throw new Options.Usage("unknown command: " + options.command());
        };
        if (report == null) {
            err.println("spider-sense: No such trace: " + options.argument());
            return Cli.NOT_FOUND;
        }
        print(out, options.flag("json") ? report.json().toJson() : report.text());
        return Options.CHECK.equals(options.command()) ? verdict(report) : Cli.OK;
    }

    /**
     * {@code export} with no server: the same document the handler streams, written
     * straight to the file or to standard output.
     *
     * <p>It is not a {@link Reports.Report} because it is not an answer to print:
     * one is a rendering of a few rows, this is the rows themselves.
     */
    private static int export(Options options, Reports reports, String service, PrintStream out,
            PrintStream err) {
        String name = options.value("out", null);
        Window window = window(options, reports, service);
        try (OutputStream file = Sessions.out(name, out)) {
            reports.export(window, service, file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + (name == null ? "the export" : name), e);
        }
        if (name != null) {
            err.println("wrote " + name);
        }
        return Cli.OK;
    }

    private static Reports.Report mark(Options options, Reports reports, String service) {
        Marks.Mark mark = reports.mark(options.argument(), options.value("note", null), service);
        return reports.mark(mark);
    }

    /**
     * The two windows, resolved the way {@code /api/compare} resolves them: the
     * end first, then {@code after} counted back from it, then {@code before}
     * counted back from that, so {@code --before=10m --after=5m} reads left to
     * right.
     */
    private static Reports.Report compare(Options options, Reports reports, String service) {
        Selectors selectors = reports.selectors();
        long now = System.currentTimeMillis();
        String until = options.value("until", null);
        long untilAt = until == null ? now : selectors.resolve(until, now, service);
        long afterAt = selectors.resolve(options.value("after", null), untilAt, service);
        long beforeAt = selectors.resolve(options.value("before", null), afterAt, service);
        return reports.compare(beforeAt, afterAt, untilAt, service, options.flag("full"));
    }

    private static Window window(Options options, Reports reports, String service) {
        return reports.selectors().window(null, null,
                options.value("since", Limits.SINCE), options.value("until", null), service);
    }

    private static int verdict(Reports.Report report) {
        Json.JsonValue pass = report.json().asObject().get("pass");
        if (pass.isNull()) {
            return Cli.NO_REQUESTS;
        }
        return pass.asBoolean() ? Cli.OK : Cli.CHECK_FAILED;
    }

    private static void print(PrintStream out, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        out.print(body);
        if (!body.endsWith("\n")) {
            out.println();
        }
        out.flush();
    }
}
