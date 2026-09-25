package net.benelog.spidersense.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import org.jspecify.annotations.Nullable;

/**
 * The CLI when nothing answers at the URL: the H2 file, opened in process for
 * the length of one command.
 *
 * <p>This is what {@code AUTO_SERVER=TRUE} buys — the application has crashed,
 * the UI went with it, and {@code findings --since=start} still answers
 * (cli.adoc#invocation). Every command works here, including {@code mark}, because a mark
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

    /**
     * The line on stderr when nothing answered at {@code base} and the file is read instead: an
     * agent must never mistake yesterday's database for a live one (cli.adoc#invocation).
     */
    static String fallbackNotice(String base, Config config) {
        return "(no Spider Sense at " + base + "; reading " + describe(config) + " directly)";
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
        String service = options.valueOrNull("service");
        if (Options.EXPORT.equals(options.command())) {
            return export(options, reports, service, out, err);
        }
        if (Options.CHECK.equals(options.command())) {
            return check(options, reports, service, out);
        }
        if (Options.UNACK.equals(options.command())) {
            if (!reports.ackStore().unack(options.requiredArgument())) {
                err.println("spider-sense: No such acknowledgement: " + options.requiredArgument());
                return Cli.NOT_FOUND;
            }
            Output.printReport(out, options, Reports.unack(options.requiredArgument()));
            return Cli.OK;
        }
        if (Options.UNRESOLVE.equals(options.command())) {
            if (!reports.ackStore().unresolve(options.requiredArgument())) {
                err.println("spider-sense: No such resolution: " + options.requiredArgument());
                return Cli.NOT_FOUND;
            }
            Output.printReport(out, options, Reports.unresolve(options.requiredArgument()));
            return Cli.OK;
        }
        Command command = Command.named(options.command());
        if (command == null) {
            throw new Options.Usage("unknown command: " + options.command());
        }
        Reports.Report report = command.answer(options, reports, service);
        if (report == null) {
            err.println("spider-sense: No such trace: " + options.requiredArgument());
            return Cli.NOT_FOUND;
        }
        Output.printReport(out, options, report);
        return Cli.OK;
    }

    /** {@code check}: the report, and its verdict as the exit code (check.adoc#exit-codes). */
    private static int check(Options options, Reports reports, @Nullable String service, PrintStream out) {
        Reports.CheckReport checked = reports.check(window(options, reports, service), service,
                options.valueOrNull("endpoint"), options.rules());
        Output.printReport(out, options, checked.report());
        return switch (checked.verdict()) {
            case PASS -> Cli.OK;
            case FAIL -> Cli.CHECK_FAILED;
            case NONE -> Cli.NO_REQUESTS;
        };
    }

    /**
     * {@code export} with no server: the same document the handler streams, written
     * straight to the file or to standard output.
     *
     * <p>It is not a {@link Reports.Report} because it is not an answer to print:
     * one is a rendering of a few rows, this is the rows themselves.
     */
    private static int export(Options options, Reports reports, @Nullable String service, PrintStream out,
            PrintStream err) {
        String name = options.valueOrNull("out");
        Window window = window(options, reports, service);
        try (OutputStream file = Sessions.out(name, out)) {
            reports.export(window, service, file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + (name == null ? "the export" : name), e);
        }
        Output.wroteExport(err, name);
        return Cli.OK;
    }

    /** The window every windowed command reads, resolved against the store's clock. */
    static Window window(Options options, Reports reports, @Nullable String service) {
        return reports.selectors().window(null, null,
                options.value("since", Selectors.DEFAULT_SINCE), options.valueOrNull("until"), service);
    }
}
