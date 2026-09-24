package net.benelog.spidersense.store;

import org.jspecify.annotations.Nullable;

/**
 * The tables, verbatim from storage.adoc#schema.
 *
 * <p>Created with {@code IF NOT EXISTS} at every start, because several Spider
 * Sense processes open the same database and any of them may be the first. A
 * future version that changes a table bumps {@link #VERSION} and drops what it
 * finds: the contents are a cache of a development session, not a record worth
 * migrating.
 */
public final class Schema {

    public static final int VERSION = 8;

    /**
     * The H2 user {@code POST /api/sql} runs on: {@code SELECT} on {@code PUBLIC}
     * and nothing else (storage.adoc#schema).
     *
     * <p>It is the second of the three layers that keep the escape hatch read-only,
     * and the only one that is not a guess: a statement allowlist can be fooled by
     * a string literal, but a user without the right cannot write whatever the
     * parser thought it saw. H2 refuses {@code INSERT}, {@code UPDATE},
     * {@code DELETE}, {@code DROP} and {@code ALTER} to it with "Not enough rights
     * for object", and {@code FILE_WRITE}, {@code CSVWRITE}, {@code FILE_READ},
     * {@code LINK_SCHEMA} and {@code RUNSCRIPT} with "Admin rights are required for
     * this operation", because it is not an administrator.
     *
     * <p>The password is a constant: the database is a local development cache
     * behind no port of its own, and a secret nobody can tell anybody would only
     * be a secret from the next Spider Sense to open the file.
     */
    public static final String READER = "spider_sense_reader";

    static final String READER_PASSWORD = "spider-sense-reader";

    private Schema() {
    }

    static final String[] TABLES = {
            """
            CREATE TABLE IF NOT EXISTS service (
                name         VARCHAR(255) PRIMARY KEY,
                language     VARCHAR(64),
                pid          BIGINT,
                first_seen   BIGINT NOT NULL,
                last_seen    BIGINT NOT NULL,
                resource     VARCHAR(65535) NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS span (
                id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                trace_id       CHAR(32) NOT NULL,
                span_id        CHAR(16) NOT NULL,
                parent_span_id CHAR(16),
                service        VARCHAR(255) NOT NULL,
                name           VARCHAR(1024) NOT NULL,
                kind           VARCHAR(8) NOT NULL,
                start_ms       BIGINT NOT NULL,
                start_ns       BIGINT NOT NULL,
                duration_ns    BIGINT NOT NULL,
                status         VARCHAR(5) NOT NULL,
                status_message VARCHAR(4096),
                entry          BOOLEAN NOT NULL,
                error          BOOLEAN NOT NULL,
                slow           BOOLEAN NOT NULL,
                category       VARCHAR(10) NOT NULL,
                endpoint       VARCHAR(1024),
                endpoint_id    CHAR(12),
                http_method    VARCHAR(16),
                http_route     VARCHAR(1024),
                http_status    INT,
                db_system      VARCHAR(64),
                db_statement   VARCHAR(4096),
                db_namespace   VARCHAR(255),
                db_operation   VARCHAR(64),
                db_table       VARCHAR(255),
                query_id       CHAR(12),
                error_type     VARCHAR(512),
                error_message  VARCHAR(4096),
                error_id       CHAR(12),
                scope          VARCHAR(255),
                attributes     VARCHAR(65535) NOT NULL,
                events         VARCHAR(65535) NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS span_start ON span (start_ms)",
            "CREATE INDEX IF NOT EXISTS span_trace ON span (trace_id)",
            "CREATE INDEX IF NOT EXISTS span_service ON span (service, start_ms)",
            "CREATE INDEX IF NOT EXISTS span_endpoint ON span (endpoint_id, start_ms)",
            "CREATE INDEX IF NOT EXISTS span_query ON span (query_id, start_ms)",
            "CREATE INDEX IF NOT EXISTS span_error ON span (error_id, start_ms)",
            """
            CREATE TABLE IF NOT EXISTS trace (
                trace_id     CHAR(32) PRIMARY KEY,
                start_ms     BIGINT NOT NULL,
                end_ms       BIGINT NOT NULL,
                duration_ns  BIGINT NOT NULL,
                root_span_id CHAR(16),
                root_name    VARCHAR(1024) NOT NULL,
                root_service VARCHAR(255) NOT NULL,
                root_kind    VARCHAR(8) NOT NULL,
                services     VARCHAR(4096) NOT NULL,
                span_count   INT NOT NULL,
                error_count  INT NOT NULL,
                db_count     INT NOT NULL,
                http_status  INT,
                slow         BOOLEAN NOT NULL,
                error        BOOLEAN NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS trace_start ON trace (start_ms)",
            "CREATE INDEX IF NOT EXISTS trace_service ON trace (root_service, start_ms)",
            """
            CREATE TABLE IF NOT EXISTS log (
                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                at_ms           BIGINT NOT NULL,
                service         VARCHAR(255) NOT NULL,
                severity_number INT NOT NULL,
                severity        VARCHAR(8) NOT NULL,
                body            VARCHAR(65535) NOT NULL,
                logger          VARCHAR(512),
                trace_id        CHAR(32),
                span_id         CHAR(16),
                attributes      VARCHAR(65535) NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS log_at ON log (at_ms)",
            "CREATE INDEX IF NOT EXISTS log_trace ON log (trace_id)",
            "CREATE INDEX IF NOT EXISTS log_service ON log (service, at_ms)",
            """
            CREATE TABLE IF NOT EXISTS metric (
                service     VARCHAR(255) NOT NULL,
                name        VARCHAR(255) NOT NULL,
                type        VARCHAR(12) NOT NULL,
                unit        VARCHAR(64),
                description VARCHAR(1024),
                monotonic   BOOLEAN NOT NULL,
                temporality VARCHAR(12),
                PRIMARY KEY (service, name)
            )""",
            """
            CREATE TABLE IF NOT EXISTS metric_series (
                id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                service    VARCHAR(255) NOT NULL,
                name       VARCHAR(255) NOT NULL,
                attr_hash  CHAR(12) NOT NULL,
                attributes VARCHAR(4096) NOT NULL,
                CONSTRAINT metric_series_key UNIQUE (service, name, attr_hash)
            )""",
            """
            CREATE TABLE IF NOT EXISTS metric_point (
                series_id BIGINT NOT NULL,
                at_ms     BIGINT NOT NULL,
                value     DOUBLE,
                count     BIGINT,
                sum       DOUBLE,
                min       DOUBLE,
                max       DOUBLE,
                buckets   VARCHAR(8192),
                PRIMARY KEY (series_id, at_ms)
            )""",
            """
            CREATE TABLE IF NOT EXISTS tingle (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                at_ms       BIGINT NOT NULL,
                kind        VARCHAR(16) NOT NULL,
                service     VARCHAR(255) NOT NULL,
                title       VARCHAR(1024) NOT NULL,
                detail      VARCHAR(4096) NOT NULL,
                trace_id    CHAR(32),
                span_id     CHAR(16),
                duration_ms DOUBLE
            )""",
            "CREATE INDEX IF NOT EXISTS tingle_at ON tingle (at_ms)",
            """
            CREATE TABLE IF NOT EXISTS mark (
                id       BIGINT AUTO_INCREMENT PRIMARY KEY,
                at_ms    BIGINT NOT NULL,
                name     VARCHAR(64) NOT NULL,
                service  VARCHAR(255),
                note     VARCHAR(1024)
            )""",
            "CREATE INDEX IF NOT EXISTS mark_at ON mark (at_ms)",
            """
            CREATE TABLE IF NOT EXISTS ack (
                finding_id VARCHAR(64) PRIMARY KEY,
                at_ms      BIGINT NOT NULL,
                note       VARCHAR(1024),
                resolved   BOOLEAN NOT NULL DEFAULT FALSE
            )""",
            """
            CREATE TABLE IF NOT EXISTS db_table (
                service     VARCHAR(255) NOT NULL,
                schema_name VARCHAR(255) NOT NULL,
                table_name  VARCHAR(255) NOT NULL,
                product     VARCHAR(64),
                indexes     VARCHAR(65535) NOT NULL,
                seen_ms     BIGINT NOT NULL,
                PRIMARY KEY (service, schema_name, table_name)
            )""",
            """
            CREATE TABLE IF NOT EXISTS meta (
                key   VARCHAR(64) PRIMARY KEY,
                value VARCHAR(4096) NOT NULL
            )""",
    };

    /**
     * The tables the data lives in, in the order they must be emptied.
     *
     * <p>{@code ack} is in this list and in none of the sweeper's: an
     * acknowledgement is not swept by time, since a known finding stays known, but
     * {@code DELETE /api/data} empties it with everything else (storage.adoc).
     */
    static final String[] DATA_TABLES =
            {"span", "trace", "log", "metric_point", "tingle", "mark", "ack", "db_table"};

    static void create(Sql sql) {
        create(sql, true);
    }

    /**
     * Checks the stored version first, then creates what is missing.
     *
     * <p>The order matters both ways. A database of another version may hold a
     * table whose old shape an index of this version cannot be created on, so its
     * tables are dropped before {@link #TABLES} runs, not after. And a refused open
     * must leave the database as it found it, without a table of this version
     * created in it.
     *
     * @param upgrade whether a database of another version is dropped and recreated
     *                (the server's way) or refused (the CLI's way: a command that
     *                reads a file must never empty it under a running older server)
     */
    static void create(Sql sql, boolean upgrade) {
        Long stored = storedVersion(sql);
        if (stored == null && !upgrade && sql.count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC'", java.util.List.of()) > 0) {
            // Tables but no meta: an H2 database Spider Sense never wrote, such as the
            // application's own named by a mistaken --db. The CLI creates nothing in it; an
            // empty database has nothing to spoil and gets this version's tables.
            throw new IllegalStateException("the database holds tables but no Spider Sense schema"
                    + "; point --db at the file an application under the agent or the standalone server writes");
        }
        if (stored != null && stored != VERSION && !upgrade) {
            throw new IllegalStateException("the database is schema version " + stored
                    + " and this Spider Sense expects " + VERSION
                    + "; start an application or the standalone server with this version first"
                    + " (it recreates the tables), or point --db at another file");
        }
        if (stored != null && stored != VERSION) {
            for (String table : new String[]{"span", "trace", "log", "metric_point", "metric_series",
                    "metric", "tingle", "mark", "ack", "db_table", "service"}) {
                sql.execute("DROP TABLE IF EXISTS " + table);
            }
        }
        sql.execute(TABLES);
        if (stored == null) {
            sql.update("MERGE INTO meta (key, value) KEY(key) VALUES (?, ?)",
                    java.util.List.of("schema_version", String.valueOf(VERSION)));
            sql.update("MERGE INTO meta (key, value) KEY(key) VALUES (?, ?)",
                    java.util.List.of("created_at", String.valueOf(System.currentTimeMillis())));
        } else if (stored != VERSION) {
            sql.update("MERGE INTO meta (key, value) KEY(key) VALUES (?, ?)",
                    java.util.List.of("schema_version", String.valueOf(VERSION)));
        }
        if (upgrade) {
            reader(sql);
        }
    }

    /** The {@code schema_version} in {@code meta}, or null for a database without one. */
    private static @Nullable Long storedVersion(Sql sql) {
        long meta = sql.count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'META'", java.util.List.of());
        if (meta == 0) {
            return null;
        }
        return sql.queryOne("SELECT value FROM meta WHERE key = 'schema_version'",
                java.util.List.of(), rs -> Long.valueOf(rs.getString(1)));
    }

    /**
     * Creates {@link #READER} and grants it {@code SELECT}, idempotently.
     *
     * <p>Only on the server's open. The CLI reads a database that a running older
     * Spider Sense may own, and creating a user in it is a change to somebody
     * else's database; a CLI that finds no reader says so instead
     * ({@link Database#reader()}).
     *
     * <p>The grant is on the schema rather than on the tables, so a table this
     * version does not have yet is covered the moment it is created, and no
     * version bump is needed for a right that never changes.
     */
    static void reader(Sql sql) {
        sql.execute(
                "CREATE USER IF NOT EXISTS " + READER + " PASSWORD '" + READER_PASSWORD + "'",
                "GRANT SELECT ON SCHEMA PUBLIC TO " + READER);
    }
}
