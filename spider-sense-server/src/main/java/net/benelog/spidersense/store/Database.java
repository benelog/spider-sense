package net.benelog.spidersense.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.h2.api.ErrorCode;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.message.DbException;
import org.jspecify.annotations.Nullable;

/**
 * The H2 database behind everything: opened, schema-checked, and pooled.
 *
 * <p>A file under the user's home, shared with {@code AUTO_SERVER=TRUE}, is what
 * lets the UI outlive the monitored application and lets several Spider Sense
 * processes look at the same data (storage.adoc#where). The one thing this must never do
 * is fail: a corrupt file or a read-only home would otherwise take the monitored
 * application's {@code premain} with it, so an unopenable database falls back to
 * an in-memory one and says so on {@code /api/status}.
 */
public final class Database implements AutoCloseable {

    /** What {@code /api/status.storage} reports, minus the writer's counters. */
    public record Storage(String url, @Nullable String path, long sizeBytes, boolean fallback,
            @Nullable String fallbackReason) {
    }

    /**
     * No read-only connection to be had: the database has no reader user yet, or
     * H2 would not open one. {@code sql} reports it as it reports a refused
     * statement, because the statement cannot run and the message says what to do
     * (cli.adoc#sql); a type of its own, so that no other
     * {@code IllegalStateException} is taken for it.
     */
    public static final class ReaderUnavailable extends IllegalStateException {
        ReaderUnavailable(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private static final System.Logger LOG = System.getLogger(Database.class.getName());
    private static final int MAX_CONNECTIONS = 8;

    /** H2's own page cache, which this never goes below, in KiB. */
    private static final long MIN_CACHE_KB = 16 * 1024;

    /** The most page cache this takes of a heap, in KiB, however large the heap is. */
    private static final long MAX_CACHE_KB = 256 * 1024;

    /** The share of the maximum heap the page cache may take: one sixteenth. */
    private static final int CACHE_HEAP_DIVISOR = 16;

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
            sizeCache(pool, url);
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
            String reason = reason(e);
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
     *
     * <p>Only that race is retried. A corrupt file, a path that cannot be created or a
     * setting H2 refuses fails the same way every time, and this runs on the monitored
     * application's premain thread, so it falls back at once.
     */
    private static Database openWithRetry(String url, @Nullable Path file) {
        long deadline = System.currentTimeMillis() + OPEN_RETRY_MS;
        RuntimeException last = null;
        while (true) {
            try {
                return new Database(url, file, null);
            } catch (RuntimeException e) {
                last = e;
                if (file == null || !raced(e) || System.currentTimeMillis() >= deadline) {
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

    /**
     * The H2 error codes of a second process opening the file while the first one
     * does: the lock file taken or recently modified, an auto-server not yet
     * answering, and the first one's CREATE TABLE or its locks.
     */
    private static final Set<Integer> RACE_CODES = Set.of(ErrorCode.ERROR_OPENING_DATABASE_1,
            ErrorCode.DATABASE_ALREADY_OPEN_1, ErrorCode.CONNECTION_BROKEN_1,
            ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, ErrorCode.DUPLICATE_KEY_1, ErrorCode.LOCK_TIMEOUT_1);

    /**
     * What H2 said, rather than the statement it said it about: a failure wrapped by
     * {@link Sql} names only the query, and the corruption or the missing permission
     * is in its cause.
     */
    private static String reason(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException && t.getMessage() != null) {
                return t.getMessage().lines().findFirst().orElse(t.getMessage());
            }
        }
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /** Whether a failure to open is the race with another process that a retry outlasts. */
    static boolean raced(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            int code = t instanceof SQLException sql ? sql.getErrorCode()
                    : t instanceof DbException db ? db.getErrorCode() : -1;
            if (code >= 0) {
                return RACE_CODES.contains(code);
            }
        }
        return false;
    }

    /**
     * The page cache in KiB for a heap of {@code maxHeapBytes}: a sixteenth of it,
     * never below H2's own 16 MiB and never above 256 MiB (storage.adoc#reads).
     */
    static long cacheKb(long maxHeapBytes) {
        long share = maxHeapBytes / CACHE_HEAP_DIVISOR / 1024;
        return Math.max(MIN_CACHE_KB, Math.min(MAX_CACHE_KB, share));
    }

    /**
     * Sizes H2's page cache to this process's heap, when this process owns the
     * database engine.
     *
     * <p>Every read of findings and of the lists scans the window's pages, and a
     * cache smaller than them reads each page from the file again on every scan.
     * The cache lives in the heap of the process that opened the file, which is the
     * monitored application in embedded mode, so it is a share of that heap rather
     * than a fixed size. A process that joined another one's engine through
     * {@code AUTO_SERVER} leaves it alone, since the cache is not in its heap, and
     * so does a URL that names {@code CACHE_SIZE} itself.
     *
     * <p>Nothing here may stop the database from opening: a failure is logged and
     * H2's default stays.
     */
    private static void sizeCache(JdbcConnectionPool pool, String url) {
        if (url.startsWith("jdbc:h2:mem:") || url.toUpperCase(Locale.ROOT).contains("CACHE_SIZE")) {
            return;
        }
        try (Connection connection = pool.getConnection()) {
            if (!(connection.unwrap(JdbcConnection.class).getSession() instanceof SessionLocal)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET CACHE_SIZE " + cacheKb(Runtime.getRuntime().maxMemory()));
            }
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Spider Sense kept H2's page cache size: " + e.getMessage());
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
     * second layer of {@code POST /api/sql} (cli.adoc#read-only).
     *
     * <p>It is not pooled and it is not the writer's: one statement borrows it,
     * rolls back and closes it, and nothing else in Spider Sense ever holds it.
     *
     * @throws ReaderUnavailable when the database has no reader user, which is
     *                            what a database an older Spider Sense created
     *                            looks like until a server of this version
     *                            opens it, or when H2 will not connect it
     */
    public Connection reader() {
        if (!hasReader()) {
            throw new ReaderUnavailable("the database has no read-only user yet;"
                    + " start an application or the standalone server with this version first",
                    null);
        }
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(readerUrl(url));
        source.setUser(Schema.READER);
        source.setPassword(Schema.READER_PASSWORD);
        try {
            return source.getConnection();
        } catch (SQLException e) {
            throw new ReaderUnavailable("could not open a read-only connection to " + url
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * A connection as the administrator outside the pool, for the writer's flush
     * at JVM exit, when H2's own exit hook may have closed the pool's sessions
     * (storage.adoc#writer).
     *
     * <p>Opened while H2 is closing the file, it waits for the close to finish and
     * opens the file again. Joined to another process's engine through
     * {@code AUTO_SERVER}, it is one more remote session.
     */
    Connection connectDirectly() throws SQLException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        source.setPassword("");
        return source.getConnection();
    }

    /**
     * Closes the engine {@code connection} is a session of when it runs in this
     * process, as H2's own exit hook would: the other sessions go, and the file is
     * written and closed before this returns. A session of another process's
     * engine, joined through {@code AUTO_SERVER}, leaves that engine alone.
     */
    void shutdownEngine(Connection connection) throws SQLException {
        if (!(connection.unwrap(JdbcConnection.class).getSession() instanceof SessionLocal)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
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
     * Each service's newest start mark stays, so {@code since=start} still names
     * the run of a process that keeps running.
     */
    public void deleteAll() {
        for (String table : Schema.DATA_TABLES) {
            sql.update("mark".equals(table)
                    ? "DELETE FROM mark o WHERE " + Marks.NOT_NEWEST_START
                    : "DELETE FROM " + table, List.of());
        }
        Sweeper.deleteOrphanSeries(sql);
    }

    @Override
    public void close() {
        pool.dispose();
    }
}
