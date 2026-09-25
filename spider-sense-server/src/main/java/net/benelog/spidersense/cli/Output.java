package net.benelog.spidersense.cli;

import java.io.PrintStream;

import net.benelog.spidersense.api.Reports;
import org.jspecify.annotations.Nullable;

/**
 * How a command's answer reaches the terminal, the same whether {@link Remote} or {@link Local}
 * produced it: the answer on stdout, and the notes about it on stderr.
 */
final class Output {

    private Output() {
    }

    /** The answer as it is, ending in a newline, flushed; nothing for an empty one. */
    static void print(PrintStream out, @Nullable String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        out.print(body);
        if (!body.endsWith("\n")) {
            out.println();
        }
        out.flush();
    }

    /** A report in the format the command line asked for: {@code --json}, else the text. */
    static void printReport(PrintStream out, Options options, Reports.Report report) {
        print(out, options.flag("json") ? report.json().toJson() : report.text());
    }

    /** The line that says where {@code export --out} wrote; nothing when it went to stdout. */
    static void wroteExport(PrintStream err, @Nullable String name) {
        if (name != null) {
            err.println("wrote " + name);
        }
    }
}
