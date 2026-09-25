package net.benelog.spidersense.api;

import net.benelog.spidersense.store.ReadOnlyQuery;
import org.jspecify.annotations.Nullable;

/**
 * How long a list is when nobody said, and how long it may be asked to be: the one table the
 * HTTP handlers, the MCP tools and the CLI all read (api.adoc, cli.adoc#options).
 *
 * <p>They live in one place because the CLI sends its limit to the server rather than letting
 * the handler's default apply: the same command must print the same rows whether it was answered
 * over HTTP or read from the H2 file. The one number the CLI does not share is {@link #CLI_TRACES}:
 * the CLI table (cli.adoc#options) says 20 traces where the handler says 50, because a terminal is
 * not a scrolling list. The SQL numbers are {@link ReadOnlyQuery}'s own, since the store enforces
 * them too.
 */
public final class Limits {

    public static final int FINDINGS = 20;
    public static final int FINDINGS_MAX = 100;
    public static final int TRACES = 50;
    public static final int TRACES_MAX = 1000;
    /** The CLI's {@code traces} default, below the handler's {@link #TRACES}. */
    public static final int CLI_TRACES = 20;
    public static final int SCATTER = 5000;
    public static final int SCATTER_MAX = 50_000;
    public static final int QUERIES = 100;
    public static final int QUERIES_MAX = 1000;
    public static final int ERRORS = 100;
    public static final int ERRORS_MAX = 1000;
    public static final int LOGS = 200;
    public static final int LOGS_MAX = 5000;
    public static final int MARKS = 50;
    public static final int MARKS_MAX = 500;
    public static final int ACKS = 200;
    public static final int ACKS_MAX = 1000;
    public static final int SQL = ReadOnlyQuery.LIMIT;
    public static final int SQL_MAX = ReadOnlyQuery.LIMIT_MAX;
    /** The tingles the overview lists; not a parameter. */
    public static final int OVERVIEW_TINGLES = 50;
    /** The longest statement a line of text carries unless {@code full} was asked for. */
    public static final int STATEMENT_CHARS = 200;

    private Limits() {
    }

    /** The limit asked for, kept within {@code [1, max]}, or the fallback when nothing was asked. */
    public static int clamp(@Nullable Integer asked, int fallback, int max) {
        return asked == null ? fallback : Math.clamp(asked, 1, max);
    }
}
