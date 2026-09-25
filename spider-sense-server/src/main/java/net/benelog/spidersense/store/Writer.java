package net.benelog.spidersense.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Write-behind: the OTLP handler hands over a {@link Batch} and returns, and one
 * daemon thread does the SQL.
 *
 * <p>An exporter measures the collector by how fast it answers, and an answer
 * that waits for a disk commit turns a hiccup in the monitored application's own
 * I/O into backpressure on its telemetry. So the queue is bounded and lossy: when
 * it is full the oldest batch goes, and {@code /api/status} says how many were
 * dropped. Losing a second of local development telemetry is a fair price for
 * never slowing the application down.
 *
 * <p>Every flush is one transaction. After the span inserts it recomputes the
 * {@code trace} summary rows of the trace ids the flush touched — a trace arrives
 * in several exports and from several services, so the summary is only ever
 * correct as a re-aggregation — and then publishes the flush's tingles.
 */
public final class Writer implements AutoCloseable {

    /** Batches, not records: the queue holds whole exports so a drop loses a coherent chunk. */
    private static final int QUEUE_CAPACITY = 10_000;
    private static final long FLUSH_INTERVAL_MS = 200;
    private static final int FLUSH_RECORDS = 500;

    /** How often the flush at exit tries before it gives up. */
    private static final int EXIT_ATTEMPTS = 3;

    private static final System.Logger LOG = System.getLogger(Writer.class.getName());

    private final BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Long> seriesIds = new ConcurrentHashMap<>();
    private final AtomicLong droppedBatches = new AtomicLong();
    private final Object flushLock = new Object();
    private final Object wakeUp = new Object();

    private final Sql sql;
    private final EventBus events;
    private final Tingles tingles;
    private final TraceSummaries traces;
    private final LongSupplier clock;
    private final Thread thread;

    private final AtomicInteger queuedRecords = new AtomicInteger();

    /** What a flush could not write once the JVM had begun to exit; guarded by {@link #flushLock}. */
    private final List<Batch> unwritten = new ArrayList<>();

    private volatile boolean running = true;
    private volatile boolean exiting;
    /** Whether a flush wrote through the pool after the exit began; guarded by {@link #flushLock}. */
    private boolean wroteWhileExiting;
    private volatile @Nullable Thread exitHook;

    public Writer(Sql sql, EventBus events, Tingles tingles) {
        this(sql, events, tingles, System::currentTimeMillis);
    }

    /** @param clock what a flush's {@code ingested} event is stamped with, in epoch milliseconds */
    public Writer(Sql sql, EventBus events, Tingles tingles, LongSupplier clock) {
        this.sql = sql;
        this.clock = clock;
        this.events = events;
        this.tingles = tingles;
        this.traces = new TraceSummaries(tingles.slowRequestMs());
        this.thread = new Thread(this::loop, "spider-sense-writer");
        // Daemon: in agent mode the monitored application's main must be able to return.
        this.thread.setDaemon(true);
    }

    /**
     * Starts the flush thread and registers the flush at JVM exit.
     *
     * @param database the database {@link #sql} pools, which the flush at exit
     *                 reaches outside the pool and closes, see {@link #exit(Database)}
     */
    public Writer start(Database database) {
        thread.start();
        Thread hook = new Thread(() -> exit(database), "spider-sense-writer-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        exitHook = hook;
        return this;
    }

    /** Hands a decoded export over. Never blocks, never throws. */
    public void submit(Batch batch) {
        if (batch.isEmpty()) {
            return;
        }
        while (!queue.offer(batch)) {
            if (queue.poll() == null) {
                return;
            }
            droppedBatches.incrementAndGet();
        }
        int queued = queuedRecords.addAndGet(batch.recordCount());
        if (queued >= FLUSH_RECORDS) {
            synchronized (wakeUp) {
                wakeUp.notifyAll();
            }
        }
    }

    public long droppedBatches() {
        return droppedBatches.get();
    }

    public int queuedBatches() {
        return queue.size();
    }

    /** Writes everything queued, on the calling thread. The tests' synchronisation point. */
    public void flushNow() {
        flush();
    }

    /** Flushes until the queue stays empty, or the timeout passes. */
    public void awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            flush();
        } while (!queue.isEmpty() && System.currentTimeMillis() < deadline);
    }

    @Override
    public void close() {
        running = false;
        synchronized (wakeUp) {
            wakeUp.notifyAll();
        }
        flush();
        Thread hook = exitHook;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is exiting already, and the hook is running or about to.
                LOG.log(System.Logger.Level.DEBUG, "Spider Sense kept its exit flush: " + e.getMessage());
            }
            exitHook = null;
        }
    }

    /**
     * The flush at JVM exit, and the close of the database after it
     * (storage.adoc#writer).
     *
     * <p>H2 closes a file database from an exit hook of its own, and the JVM runs
     * its hooks concurrently in no set order, so the pool's sessions may be closed
     * under this flush. {@code DB_CLOSE_ON_EXIT=FALSE}, which would turn H2's hook
     * off, is refused beside {@code AUTO_SERVER=TRUE}, so the race cannot be
     * avoided; it is survived instead. What is queued, and what a flush the exit
     * interrupted could not write, goes through a connection of its own. Opened
     * while H2 is closing the file, it waits for the close to finish and opens the
     * file again; a write that H2 closed under it is tried again the same way.
     * Once the rows are in, the engine this process holds is shut down, so the
     * file is written and closed before the JVM halts, whichever of the two hooks
     * ran first. Nothing to write and nothing written since the exit began means
     * nothing to close: H2's own hook does that.
     */
    void exit(Database database) {
        exiting = true;
        synchronized (flushLock) {
            List<Batch> batches = new ArrayList<>(unwritten);
            unwritten.clear();
            queue.drainTo(batches);
            queuedRecords.set(0);
            if (batches.isEmpty() && !wroteWhileExiting) {
                return;
            }
            Throwable failure = null;
            for (int attempt = 0; attempt < EXIT_ATTEMPTS; attempt++) {
                try (Connection connection = database.connectOutsidePool()) {
                    if (!batches.isEmpty()) {
                        writeInTransaction(connection, batches);
                        batches = List.of();
                    }
                    database.shutdownEngine(connection);
                    return;
                } catch (SQLException | RuntimeException | StackOverflowError e) {
                    if (failure != null) {
                        e.addSuppressed(failure);
                    }
                    failure = e;
                }
            }
            LOG.log(System.Logger.Level.WARNING, batches.isEmpty()
                    ? "Spider Sense could not close its database at exit"
                    : "Spider Sense could not store its last batch at exit", failure);
        }
    }

    private void loop() {
        while (running) {
            try {
                synchronized (wakeUp) {
                    wakeUp.wait(FLUSH_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            flushLoggingFailure();
        }
        flushLoggingFailure();
    }

    /**
     * A flush that cannot end the thread: once it did, every later batch would be
     * dropped with nothing saying why. What a flush itself cannot store it already
     * reports; this catches what escapes that, such as a listener's failure.
     */
    private void flushLoggingFailure() {
        try {
            flush();
        } catch (RuntimeException | Error e) {
            LOG.log(System.Logger.Level.WARNING, "Spider Sense's writer survived a failed flush", e);
        }
    }

    private void flush() {
        synchronized (flushLock) {
            if (exiting) {
                // The flush at exit writes what is queued. Through the pool, after H2
                // closed the file, this would open it again behind that flush's back.
                return;
            }
            List<Batch> batches = new ArrayList<>();
            queue.drainTo(batches);
            queuedRecords.set(0);
            if (batches.isEmpty()) {
                return;
            }
            List<Batch> stored;
            try {
                writeThroughPool(batches);
                stored = batches;
            } catch (SQLException | RuntimeException | StackOverflowError e) {
                // A StackOverflowError too: an absurdly nested attribute value overflows the
                // JSON encoder, and that costs its export like any other value the store refuses.
                if (exiting) {
                    // Most likely H2's own exit hook closing the database; the flush at exit writes it.
                    unwritten.addAll(batches);
                    return;
                }
                if (batches.size() == 1) {
                    LOG.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", e);
                    return;
                }
                // A value the store refuses costs the export that carried it, not every export drained beside it.
                stored = writeOneByOne(batches);
            }
            if (exiting) {
                // Possibly into a file the pool opened again after H2 closed it; the flush at exit closes it.
                wroteWhileExiting = true;
            }
            publish(stored);
        }
    }

    /** Each batch of a failed flush in a transaction of its own, returning the ones stored. */
    private List<Batch> writeOneByOne(List<Batch> batches) {
        List<Batch> stored = new ArrayList<>(batches.size());
        for (int b = 0; b < batches.size(); b++) {
            try {
                writeThroughPool(List.of(batches.get(b)));
                stored.add(batches.get(b));
            } catch (SQLException | RuntimeException | StackOverflowError e) {
                if (exiting) {
                    unwritten.addAll(batches.subList(b, batches.size()));
                    break;
                }
                LOG.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", e);
            }
        }
        return stored;
    }

    /**
     * One flush through a pooled connection. It returns once the transaction has
     * committed: a failure to hand the connection back after that loses nothing,
     * so it is not reported as a failed write.
     */
    private void writeThroughPool(List<Batch> batches) throws SQLException {
        Connection connection = sql.connection();
        try {
            writeInTransaction(connection, batches);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "Spider Sense could not return a connection: " + e.getMessage());
            }
        }
    }

    /** One flush as one transaction on {@code connection}, pooled or not, which stays open. */
    private void writeInTransaction(Connection connection, List<Batch> batches) throws SQLException {
        // Work always answers with something; there is nothing to answer with here.
        Boolean unused = Sql.inTransaction(connection, c -> {
            Set<String> touched = insertSpans(c, batches);
            insertLogs(c, batches);
            insertTingles(c, batches);
            mergeCatalogs(c, batches);
            mergeServices(c, batches);
            writeMetrics(c, batches);
            traces.merge(c, touched);
            return Boolean.TRUE;
        });
    }

    private void publish(List<Batch> batches) {
        long now = clock.getAsLong();
        for (Batch batch : batches) {
            for (Tingle tingle : batch.tingles()) {
                events.publish("tingle", tingle);
            }
        }
        events.ingested(now);
    }

    // --- spans ---

    Set<String> insertSpans(Connection connection, List<Batch> batches) throws SQLException {
        Set<String> touched = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(SpanRow.INSERT)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (SpanRecord span : batch.spans()) {
                    SpanRow.of(span, tingles).bind(statement);
                    statement.addBatch();
                    touched.add(span.traceId());
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
        return touched;
    }

    // --- logs, tingles, catalogs, services, metrics ---

    private void insertLogs(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LogRow.INSERT)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (LogRecord log : batch.logs()) {
                    LogRow.of(log).bind(statement);
                    statement.addBatch();
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    private void insertTingles(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TingleRow.INSERT)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (Tingle tingle : batch.tingles()) {
                    TingleRow.of(tingle).bind(statement);
                    statement.addBatch();
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    /**
     * The catalog rows of a flush, merged in the flush's own transaction.
     *
     * <p>A merge rather than an insert because the extension reads a table's
     * indexes once per process: the row of a table looked up again after a restart
     * is the newer truth about the same table, and replaces the older one
     * (storage.adoc#writer).
     */
    private void mergeCatalogs(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CatalogRow.MERGE)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (Batch.Catalog catalog : batch.catalogs()) {
                    CatalogRow.of(catalog).bind(statement);
                    statement.addBatch();
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    /**
     * {@code first_seen} must survive, so this is an update-then-insert rather
     * than a MERGE that would overwrite it.
     *
     * <p>The start marks are read first, because a sighting whose
     * {@code process.pid} is new to this service means the application was
     * restarted, and that moment is worth a {@code start} mark: it is what
     * {@code since=start} resolves to, and nobody had to ask for it
     * (marks-and-compare.adoc#start-marks).
     */
    private void mergeServices(Connection connection, List<Batch> batches) throws SQLException {
        Map<String, Batch.Sighting> sightings = new LinkedHashMap<>();
        for (Batch batch : batches) {
            for (Batch.Sighting sighting : batch.services()) {
                sightings.put(sighting.name(), sighting);
            }
        }
        for (Batch.Sighting sighting : sightings.values()) {
            ServiceRow row = ServiceRow.of(sighting);
            markRestart(connection, row, startOf(batches, sighting));
            try (PreparedStatement update = connection.prepareStatement(ServiceRow.UPDATE)) {
                row.bindUpdate(update);
                if (update.executeUpdate() > 0) {
                    continue;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(ServiceRow.INSERT)) {
                row.bindInsert(insert);
                insert.executeUpdate();
            }
        }
    }

    /** How far before its receipt a run's first record may have started (a metric export interval). */
    private static final long START_MARK_MAX_LEAD_MS = 60_000;

    /**
     * When a sighting's run began, as far as this flush tells: the earliest span
     * start, log record or metric point of its service, never after the sighting's
     * receipt and never more than {@link #START_MARK_MAX_LEAD_MS} before it. An exporter
     * batches for seconds, so the first requests of a run arrive after they began,
     * and a start mark at the receipt would leave them out of {@code since=start}.
     */
    private static long startOf(List<Batch> batches, Batch.Sighting sighting) {
        long start = sighting.at();
        for (Batch batch : batches) {
            for (SpanRecord span : batch.spans()) {
                if (span.service().equals(sighting.name())) {
                    start = Math.min(start, span.startMillis());
                }
            }
            for (LogRecord log : batch.logs()) {
                if (log.service().equals(sighting.name())) {
                    start = Math.min(start, log.at());
                }
            }
            for (Batch.MetricSample sample : batch.metrics()) {
                if (sample.service().equals(sighting.name())) {
                    start = Math.min(start, sample.point().at());
                }
            }
        }
        return Math.max(start, sighting.at() - START_MARK_MAX_LEAD_MS);
    }

    /**
     * A {@code start} mark for a service whose process id is new.
     *
     * <p>"New" is a process id that no {@code start} mark of the service names yet:
     * the restart an agent has just caused by rebuilding and running the
     * application again, or the first sighting of the service. It is not decided
     * against the one pid the service row stores, because two processes may export
     * one service name at once, an application and its tests or two instances of
     * one application, and each export of the other would then count as a restart.
     * A sighting without a {@code process.pid} marks nothing, since there is
     * nothing to compare.
     */
    private void markRestart(Connection connection, ServiceRow service, long at) throws SQLException {
        Long pid = service.pid();
        if (pid == null) {
            return;
        }
        MarkRow mark = new MarkRow(at, Marks.START, service.name(), "pid " + pid);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM mark WHERE name = ? AND service = ? AND note = ?")) {
            select.setString(1, mark.name());
            select.setString(2, mark.service());
            select.setString(3, mark.note());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(MarkRow.INSERT)) {
            mark.bind(insert);
            insert.executeUpdate();
        }
    }

    void writeMetrics(Connection connection, List<Batch> batches) throws SQLException {
        // Keyed by service as well as name: two services may export one name as
        // different instruments, and each one's points mean what its own metadata says.
        Map<String, Batch.MetricSample> metadata = new LinkedHashMap<>();
        List<Batch.MetricSample> samples = new ArrayList<>();
        for (Batch batch : batches) {
            for (Batch.MetricSample sample : batch.metrics()) {
                metadata.put(sample.service() + "\0" + sample.name(), sample);
                samples.add(sample);
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(MetricRow.MERGE)) {
            for (Batch.MetricSample sample : metadata.values()) {
                MetricRow.of(sample).bind(statement);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        List<Series> series = new ArrayList<>(samples.size());
        for (Batch.MetricSample sample : samples) {
            series.add(Series.of(sample));
        }
        forgetDeletedSeries(connection, series);
        try (PreparedStatement statement = connection.prepareStatement(PointRow.MERGE)) {
            for (int s = 0; s < samples.size(); s++) {
                Batch.MetricSample sample = samples.get(s);
                PointRow.of(seriesId(connection, sample, series.get(s)), sample.point()).bind(statement);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * What identifies a sample's series: the key {@link #seriesIds} caches its id
     * under, and the {@code attr_hash} and attributes of its row.
     */
    private record Series(String cacheKey, String attrHash, String attributesJson) {

        static Series of(Batch.MetricSample sample) {
            String attributes = AttrJson.encodeSorted(sample.attributes());
            String hash = Ids.shortHash(attributes);
            return new Series(sample.service() + "\0" + sample.name() + "\0" + hash, hash, attributes);
        }
    }

    /**
     * Drops from the cache the ids of the flush's series whose rows are gone, so
     * {@link #seriesId} looks them up or creates them again.
     *
     * <p>A row can go behind this writer's back: the sweeper deletes a series that
     * has had no points for a retention window, and {@code DELETE /api/data} in any
     * process sharing the file deletes them all. A point merged with a dead id
     * would join no series and be invisible to every read. One {@code SELECT} per
     * flush that carries metrics is the price, against the few hundred lookups the
     * cache saves.
     */
    private void forgetDeletedSeries(Connection connection, List<Series> series) throws SQLException {
        Map<Long, String> cached = new LinkedHashMap<>();
        for (Series one : series) {
            Long id = seriesIds.get(one.cacheKey());
            if (id != null) {
                cached.put(id, one.cacheKey());
            }
        }
        for (List<Long> chunk : Sql.chunks(List.copyOf(cached.keySet()))) {
            Set<Long> present = new HashSet<>();
            try (PreparedStatement select = connection.prepareStatement(
                    // Locked: the orphan sweep locks a series before it deletes it, so the
                    // points this flush adds are committed before the sweep looks again.
                    "SELECT id FROM metric_series WHERE id IN (" + Sql.placeholders(chunk.size())
                            + ") FOR UPDATE")) {
                Sql.bind(select, chunk);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        present.add(rs.getLong(1));
                    }
                }
            }
            for (Long id : chunk) {
                String key = cached.get(id);
                if (key != null && !present.contains(id)) {
                    seriesIds.remove(key, id);
                }
            }
        }
    }

    /** Series ids are looked up once and cached; a JVM exports the same few hundred forever. */
    private long seriesId(Connection connection, Batch.MetricSample sample, Series series)
            throws SQLException {
        Long cached = seriesIds.get(series.cacheKey());
        if (cached != null) {
            return cached;
        }
        long id = MetricSeriesRows.lookupOrCreate(connection, sample.service(), sample.name(), series.attributesJson());
        seriesIds.put(series.cacheKey(), id);
        return id;
    }

    /**
     * {@code DELETE /api/data}: what is queued is flushed, then {@code deleteAll}
     * runs, and the series cache is emptied along with the rows, all while no
     * other flush of this writer can run. A flush committed between two of the
     * deletes would otherwise keep what one statement had already passed and lose
     * what the next one had not, such as its spans without their trace rows.
     */
    public void clear(Runnable deleteAll) {
        synchronized (flushLock) {
            flush();
            deleteAll.run();
            seriesIds.clear();
        }
    }
}
