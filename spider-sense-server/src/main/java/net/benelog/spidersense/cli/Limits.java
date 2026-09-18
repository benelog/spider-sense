package net.benelog.spidersense.cli;

import net.benelog.spidersense.store.ReadOnlyQuery;

/**
 * How long a list is when nobody said, and how long it may be asked to be.
 *
 * <p>They live in one place because the CLI sends its limit to the server rather
 * than letting the handler's default apply: the same command must print the same
 * rows whether it was answered over HTTP or read from the H2 file. The numbers
 * are the handlers' own (api.md), except {@code traces}, where agent.md's CLI
 * table says 20 — a terminal is not a scrolling list.
 */
final class Limits {

    /** What {@code since} means when nobody said (agent.md). */
    static final String SINCE = "15m";

    static final int FINDINGS = 20;
    static final int FINDINGS_MAX = 100;
    static final int TRACES = 20;
    static final int TRACES_MAX = 1000;
    static final int QUERIES = 100;
    static final int QUERIES_MAX = 1000;
    static final int ERRORS = 100;
    static final int ERRORS_MAX = 1000;
    static final int LOGS = 200;
    static final int LOGS_MAX = 5000;
    static final int MARKS = 50;
    static final int MARKS_MAX = 500;
    static final int SQL = ReadOnlyQuery.LIMIT;
    static final int SQL_MAX = ReadOnlyQuery.LIMIT_MAX;

    private Limits() {
    }
}
