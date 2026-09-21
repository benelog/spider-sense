package net.benelog.spidersense.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.jspecify.annotations.Nullable;

/**
 * The H2 database behind everything: opened, schema-checked, and pooled.
 *
 * <p>A file under the user's home, shared with {@code AUTO_SERVER=TRUE}, is what
 * lets the UI outlive the monitored application and lets several Spider Sense
 * processes look at the same data (storage.md). The one thing this must never do
 * is fail: a corrupt file or a read-only home would otherwise take the monitored
 * application's {@code premain} with it, so an unopenable database falls back to
 * an in-memory one and says so on {@code /api/status}.
 */
public final class Database implements AutoCloseable {

    /** What {@code /api/status.storage} reports, minus the writer's counters. */
    public record Storage(String url, @Nullable String path, long sizeBytes, boolean fallback,
            @Nullable String fallbackReason) {
    }

    private static final System.Logger LOG = System.getLogger(Database.class.getName());
    private static final int MAX_CONNECTIONS = 8;

    private final JdbcConnectionPool pool;
    private final Sql sql;
    private final String url;
    private final @Nullable Path file;
    private final @Nullable String fallbackReason;

    /** How long two Spider Sense processes starting at once may wait for each other's H2. */
    private static final long OPEN_RETRY_MS = 15_000;
    private static final long OPEN_RETRY_PAUSE_MS = 500;

    private Database(String url, @Nullable Path file, @Nullable String fallbackReason) {
        this(url, file, fallbackReason, true);
    }

    private Database(String url, @Nullable Path file, @Nullable String fallbackReason,
            boolean upgrade) {
        this.url = url;
        this.file = file;
        this.fallbackReason = fallbackReason;
        this.pool = JdbcConnectionPool.create(url, "sa", "");
        this.pool.setMaxConnections(MAX_CONNECTIONS);
        this.sql = new Sql(pool);
        try {
            Schema.create(sql, upgrade);
        } catch (RuntimeException e) {
            pool.dispose();
            throw e;
        }
    }

    /**
     * Opens {@code url}, or an in-memory database when that fails.
     *
     * @param file the {@code .mv.db} the URL names, or null for an in-memory URL
     */
    public static Database open(String url, @Nullable Path file) {
        try {
            if (file != null && file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            return openWithRetry(url, file);
        } catch (IOException | RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.log(System.Logger.Level.WARNING,
                    "Spider Sense could not open " + url + " (" + reason + "); keeping this session in memory");
            String memory = "jdbc:h2:mem:spidersense-" + ProcessHandle.current().pid()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE";
            return new Database(memory, null, reason);
        }
    }

    /**
     * The CLI's open: the file must already exist, its schema must be this version,
     * and there is no in-memory fallback, because a command that answers from an
     * empty database instead of saying why would be worse than no answer.
     *
     * <p>Refusing another schema version matters: {@code AUTO_SERVER=TRUE} joins the
     * database of whatever Spider Sense is running, and the server's own open would
     * drop that server's tables from under it.
     *
     * @throws IllegalStateException when the file is missing or of another version
     */
    public static Database openExisting(String url, @Nullable Path file) {
        if (file != null && !Files.isRegularFile(file)) {
            throw new IllegalStateException("no Spider Sense database at " + file
                    + "; start an application with -javaagent:spider-sense.jar first");
        }
        try {
            return new Database(url, file, null, false);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not open " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Two applications started at the same moment both open the shared file: the first
     * takes it and starts the auto-server, and the second, arriving before the lock file
     * carries the server's address, is refused with "Lock file recently modified" (or
     * races the first one's CREATE TABLE). H2 does not wait for that itself, so this does:
     * a file-backed URL is retried for a few seconds before the caller gives up on it.
     */
    private static Database openWithRetry(String url, @Nullable Path file) {
        long deadline = System.currentTimeMillis() + OPEN_RETRY_MS;
        RuntimeException last = null;
        while (true) {
            try {
                return new Database(url, file, null);
            } catch (RuntimeException e) {
                last = e;
                if (file == null || System.currentTimeMillis() >= deadline) {
                    throw last;
                }
                LOG.log(System.Logger.Level.DEBUG, "Spider Sense retrying to open " + url + ": " + e.getMessage());
                try {
                    Thread.sleep(OPEN_RETRY_PAUSE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
    }

    public Sql sql() {
        return sql;
    }

    public String url() {
        return url;
    }

    /**
     * A connection as {@link Schema#READER}, which may only {@code SELECT}: the
     * second layer of {@code POST /api/sql} (agent.md).
     *
     * <p>It is not pooled and it is not the writer's: one statement borrows it,
     * rolls back and closes it, and nothing else in Spider Sense ever holds it.
     *
     * @throws IllegalStateException when the database has no reader user, which is
     *                               what a database an older Spider Sense created
     *                               looks like until a server of this version
     *                               opens it
     */
    public Connection reader() {
        if (!hasReader()) {
            throw new IllegalStateException("the database has no read-only user yet;"
                    + " start an application or the standalone server with this version first");
        }
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(readerUrl(url));
        source.setUser(Schema.READER);
        source.setPassword(Schema.READER_PASSWORD);
        try {
            return source.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a read-only connection to " + url
                    + ": " + e.getMessage(), e);
        }
    }

    private boolean hasReader() {
        return sql.count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = ?",
                List.of(Schema.READER.toUpperCase(Locale.ROOT))) > 0;
    }

    /**
     * The settings a reader's URL keeps; every other one is dropped.
     *
     * <p>H2 turns most {@code ;NAME=VALUE} settings into a {@code SET} at connect
     * time, and several of those — {@code DB_CLOSE_DELAY} among them, which the
     * in-memory fallback sets — are refused to anyone but an administrator, so the
     * reader could not open the database at all. These two are the only settings
     * Spider Sense puts in a URL that a reader needs: one to join the shared
     * auto-server, one to parse {@code meta}'s {@code key} and {@code value}.
     */
    private static final Set<String> READER_SETTINGS = Set.of("AUTO_SERVER", "NON_KEYWORDS");

    static String readerUrl(String url) {
        String[] parts = url.split(";", -1);
        StringBuilder reader = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String setting = parts[i];
            int equals = setting.indexOf('=');
            String name = (equals < 0 ? setting : setting.substring(0, equals))
                    .trim().toUpperCase(Locale.ROOT);
            if (READER_SETTINGS.contains(name)) {
                reader.append(';').append(setting);
            }
        }
        return reader.toString();
    }

    public Storage storage() {
        long size = 0;
        if (file != null) {
            try {
                size = Files.exists(file) ? Files.size(file) : 0;
            } catch (IOException e) {
                size = 0;
            }
        }
        return new Storage(url, file == null ? null : file.toString(), size,
                fallbackReason != null, fallbackReason);
    }

    /**
     * {@code DELETE /api/data}: everything but the metadata, acknowledgements
     * included — they are the one table the sweeper never touches and this does.
     */
    public void deleteAll() {
        for (String table : Schema.DATA_TABLES) {
            sql.update("DELETE FROM " + table, List.of());
        }
        sql.update("DELETE FROM metric_series WHERE id NOT IN (SELECT series_id FROM metric_point)",
                List.of());
    }

    @Override
    public void close() {
        pool.dispose();
    }
}
