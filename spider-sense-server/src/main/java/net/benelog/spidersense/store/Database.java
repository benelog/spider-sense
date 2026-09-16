package net.benelog.spidersense.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.h2.jdbcx.JdbcConnectionPool;

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
    public record Storage(String url, String path, long sizeBytes, boolean fallback,
            String fallbackReason) {
    }

    private static final System.Logger LOG = System.getLogger(Database.class.getName());
    private static final int MAX_CONNECTIONS = 8;

    private final JdbcConnectionPool pool;
    private final Sql sql;
    private final String url;
    private final Path file;
    private final String fallbackReason;

    private Database(String url, Path file, String fallbackReason) {
        this.url = url;
        this.file = file;
        this.fallbackReason = fallbackReason;
        this.pool = JdbcConnectionPool.create(url, "sa", "");
        this.pool.setMaxConnections(MAX_CONNECTIONS);
        this.sql = new Sql(pool);
        Schema.create(sql);
    }

    /**
     * Opens {@code url}, or an in-memory database when that fails.
     *
     * @param file the {@code .mv.db} the URL names, or null for an in-memory URL
     */
    public static Database open(String url, Path file) {
        try {
            if (file != null && file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            return new Database(url, file, null);
        } catch (IOException | RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.log(System.Logger.Level.WARNING,
                    "Spider Sense could not open " + url + " (" + reason + "); keeping this session in memory");
            String memory = "jdbc:h2:mem:spidersense-" + ProcessHandle.current().pid()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE";
            return new Database(memory, null, reason);
        }
    }

    public Sql sql() {
        return sql;
    }

    public String url() {
        return url;
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

    /** {@code DELETE /api/data} and the sweeper's unbounded form: everything but the metadata. */
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
