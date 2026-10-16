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
 * never slowing the application down. It is bounded by records as well as by
 * batches, and a flush takes a few thousand records at a time, so a burst the
 * writer cannot keep up with costs its oldest batches, counted, rather than the
 * heap, which in agent mode is the monitored application's.
 *
 * <p>Every flush is one transaction. After the span inserts it recomputes the
 * {@code trace} summary rows of the trace ids the flush touched — a trace arrives
 * in several exports and from several services, so the summary is only ever
 * correct as a re-aggregation — and then publishes the tingles the flush
 * stored, which leaves out those of a span the exporter sent again, and the
 * services whose rows it inserted.
 */
public final class Writer implements AutoCloseable {

    /** Batches: the queue holds whole exports so a drop loses a coherent chunk. */
    private static final int QUEUE_CAPACITY = 10_000;

    /**
     * Records, whatever the number of batches: 10,000 exports of the 512 spans an
     * exporter sends at most would be five million spans in the heap.
     */
    static final int QUEUE_RECORDS = 50_000;

    private static final long FLUSH_INTERVAL_MS = 200;
    private static final int FLUSH_RECORDS = 500;

    /**
     * The records a flush takes off the queue into its one transaction, a batch that
     * crosses it included: a backlog waits in the queue, where the drop of the oldest
     * applies, rather than in one transaction that builds every row of it at once.
     */
    private static final int FLUSH_MAX_RECORDS = 5_000;

    /** How often the flush at exit tries before it gives up. */
    private static final int EXIT_ATTEMPTS = 3;

    private static final System.Logger LOG = LazyLogger.of(Writer.class);

    private final BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Long> seriesIds = new ConcurrentHashMap<>();

    /**
     * The {@code service} and process id pairs whose start mark this writer has found
     * stored: such a process has been marked, and is never marked again.
     */
    private final Set<String> markedStarts = ConcurrentHashMap.newKeySet();
    private final AtomicLong droppedBatches = new AtomicLong();
    private final Object flushLock = new Object();
    private final Object wakeUp = new Object();

    private final Sql sql;
    private final EventBus events;
    private final Tingles tingles;
    private final TraceSummaries traces;
    private final LongSupplier clock;
    private final Thread thread;

    /**
     * The records of the queued batches: added once a batch is queued, taken away
     * when a flush takes it or the queue drops it, so it may be off by a batch on its
     * way in, never for longer.
     */
    private final AtomicInteger queuedRecords = new AtomicInteger();

    /** What a flush could not write once the JVM had begun to exit; guarded by {@link #flushLock}. */
    private final List<Batch> unwritten = new ArrayList<>();

    private volatile boolean running = true;
    private volatile boolean exiting;
    /** The database the flush at exit writes to, set before {@link #exiting}; null until the exit. */
    private volatile @Nullable Database exitDatabase;
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

    /**
     * Hands a decoded export over. Never blocks, never throws, until the JVM begins to
     * exit: from then on it writes the batch itself, see {@link #storeAfterExit}.
     *
     * <p>When the queue is full of batches, or the batch would take it past
     * {@link #QUEUE_RECORDS} records, the oldest batches go first, each counted.
     */
    public void submit(Batch batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (exiting) {
            storeAfterExit(batch);
            return;
        }
        queue(batch);
        if (exiting) {
            // The flush at exit began while the batch was on its way in, and may have missed it.
            storeAfterExit(null);
        }
    }

    private void queue(Batch batch) {
        int records = batch.recordCount();
        while (queuedRecords.get() + records > QUEUE_RECORDS) {
            if (!dropOldest()) {
                break;
            }
        }
        while (!queue.offer(batch)) {
            dropOldest();
        }
        int queued = queuedRecords.addAndGet(records);
        if (queued >= FLUSH_RECORDS) {
            synchronized (wakeUp) {
                wakeUp.notifyAll();
            }
        }
    }

    /** Drops the oldest queued batch and counts it, returning false when there was none. */
    private boolean dropOldest() {
        Batch oldest = queue.poll();
        if (oldest == null) {
            return false;
        }
        queuedRecords.addAndGet(-oldest.recordCount());
        droppedBatches.incrementAndGet();
        return true;
    }

    /**
     * The batches the writer has lost: dropped by the full queue, refused by the
     * database, or taken by a flush that an {@link Error} ended.
     */
    public long droppedBatches() {
        return droppedBatches.get();
    }

    public int queuedBatches() {
        return queue.size();
    }

    /** Writes everything queued, on the calling thread. The tests' synchronisation point. */
    public void flushNow() {
        flushQueued();
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
        flushQueued();
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
     * nothing to close: H2's own hook does that, unless the file is large enough
     * that its close may need to compact it ({@link Database#mayNeedCompaction}),
     * which H2's own close never does (storage.adoc#file-size). An export that
     * arrives after this has begun is written the same way by {@link #storeAfterExit}.
     */
    void exit(Database database) {
        exitDatabase = database;
        exiting = true;
        synchronized (flushLock) {
            List<Batch> batches = new ArrayList<>(unwritten);
            unwritten.clear();
            batches.addAll(take(Integer.MAX_VALUE));
            if (batches.isEmpty() && !wroteWhileExiting && !database.mayNeedCompaction()) {
                return;
            }
            writeOutsidePool(database, batches);
        }
    }

    /**
     * An export that arrives once the flush at exit has begun, with whatever is still
     * queued, written on the request thread before the receiver answers it.
     *
     * <p>The OpenTelemetry SDK sends its last spans, logs and metrics from an exit hook
     * of its own, which runs beside this writer's and usually after it has found the
     * queue empty, and the embedded server's daemon threads keep answering while the
     * hooks run. A short job's every span is in that export. So it is written as the
     * flush at exit writes, through a connection of its own, and the engine is shut
     * down again; the SDK's hook waits for the answer and the JVM for the hook, so the
     * rows are in the file before the JVM halts.
     */
    private void storeAfterExit(@Nullable Batch batch) {
        Database database = exitDatabase;
        synchronized (flushLock) {
            List<Batch> batches = new ArrayList<>(unwritten);
            unwritten.clear();
            batches.addAll(take(Integer.MAX_VALUE));
            if (batch != null) {
                batches.add(batch);
            }
            if (batches.isEmpty()) {
                return;
            }
            if (database == null) {
                // Not reached: the exit sets the database before it sets exiting. Its flush would write these.
                unwritten.addAll(batches);
                return;
            }
            writeOutsidePool(database, batches);
        }
    }

    /**
     * Writes {@code batches} through a connection of its own, in transactions of at most
     * {@link #FLUSH_MAX_RECORDS} records as a flush takes them, and then shuts the engine
     * down. A failure is tried again from the first batch not yet written, as a write
     * that H2's own exit hook closed under it is; what still fails is lost, and counted.
     */
    private void writeOutsidePool(Database database, List<Batch> batches) {
        int written = 0;
        Throwable failure = null;
        for (int attempt = 0; attempt < EXIT_ATTEMPTS; attempt++) {
            try (Connection connection = database.connectOutsidePool()) {
                while (written < batches.size()) {
                    List<Batch> transaction = upTo(batches, written, FLUSH_MAX_RECORDS);
                    writeInTransaction(connection, transaction);
                    written += transaction.size();
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
        droppedBatches.addAndGet(batches.size() - written);
        LOG.log(System.Logger.Level.WARNING, written == batches.size()
                ? "Spider Sense could not close its database at exit"
                : "Spider Sense could not store its last batch at exit", failure);
    }

    /**
     * The batches of {@code batches} from {@code from} on, as many as hold
     * {@code maxRecords} records, the batch that crosses it included.
     */
    private static List<Batch> upTo(List<Batch> batches, int from, int maxRecords) {
        int records = 0;
        int to = from;
        while (to < batches.size() && records < maxRecords) {
            records += batches.get(to).recordCount();
            to++;
        }
        return batches.subList(from, to);
    }

    private void loop() {
        while (running) {
            try {
                synchronized (wakeUp) {
                    // Behind a backlog, the next flush follows at once.
                    if (queuedRecords.get() < FLUSH_RECORDS || exiting) {
                        wakeUp.wait(FLUSH_INTERVAL_MS);
                    }
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

    /**
     * One flush: the batches at the head of the queue, up to {@link #FLUSH_MAX_RECORDS}
     * records, in one transaction.
     *
     * @return how many batches it took off the queue, stored or not
     */
    int flush() {
        synchronized (flushLock) {
            if (exiting) {
                // The flush at exit writes what is queued. Through the pool, after H2
                // closed the file, this would open it again behind that flush's back.
                return 0;
            }
            List<Batch> batches = take(FLUSH_MAX_RECORDS);
            if (batches.isEmpty()) {
                return 0;
            }
            Stored stored;
            try {
                stored = writeThroughPool(batches);
            } catch (SQLException | RuntimeException | StackOverflowError e) {
                // A StackOverflowError too: an absurdly nested attribute value overflows the
                // JSON encoder, and that costs its export like any other value the store refuses.
                if (exiting) {
                    // Most likely H2's own exit hook closing the database; the flush at exit writes it.
                    unwritten.addAll(batches);
                    return batches.size();
                }
                if (batches.size() == 1) {
                    droppedBatches.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", e);
                    return 1;
                }
                // A value the store refuses costs the export that carried it, not every export drained beside it.
                stored = writeOneByOne(batches);
            } catch (Error e) {
                // An OutOfMemoryError: the transaction rolled back, and what the flush took
                // off the queue is gone, counted as the batches a full queue drops are.
                droppedBatches.addAndGet(batches.size());
                throw e;
            }
            if (exiting) {
                // Possibly into a file the pool opened again after H2 closed it; the flush at exit closes it.
                wroteWhileExiting = true;
            }
            publish(stored);
            return batches.size();
        }
    }

    /** Flushes until what was queued when it began has been taken off the queue. */
    private void flushQueued() {
        int left = queue.size();
        while (left > 0) {
            int taken = flush();
            if (taken == 0) {
                return;
            }
            left -= taken;
        }
    }

    /**
     * The batches at the head of the queue, taken off it, up to {@code maxRecords}
     * records, the batch that crosses it included.
     */
    private List<Batch> take(int maxRecords) {
        List<Batch> batches = new ArrayList<>();
        int records = 0;
        while (records < maxRecords) {
            Batch next = queue.poll();
            if (next == null) {
                break;
            }
            batches.add(next);
            records += next.recordCount();
        }
        queuedRecords.addAndGet(-records);
        return batches;
    }

    /**
     * Each batch of a failed flush in a transaction of its own, returning what they
     * stored; a batch that fails again is lost, and counted.
     */
    private Stored writeOneByOne(List<Batch> batches) {
        List<Batch.Sighting> services = new ArrayList<>();
        List<Tingle> tingles = new ArrayList<>();
        int newTraces = 0;
        for (int b = 0; b < batches.size(); b++) {
            try {
                Stored one = writeThroughPool(List.of(batches.get(b)));
                services.addAll(one.services());
                tingles.addAll(one.tingles());
                newTraces += one.newTraces();
            } catch (SQLException | RuntimeException | StackOverflowError e) {
                if (exiting) {
                    unwritten.addAll(batches.subList(b, batches.size()));
                    break;
                }
                droppedBatches.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", e);
            } catch (Error e) {
                droppedBatches.addAndGet(batches.size() - b);
                throw e;
            }
        }
        return new Stored(services, tingles, newTraces);
    }

    /**
     * What a committed flush announces on the live stream: the services whose rows it
     * inserted, the tingles it stored, and how many {@code trace} rows it inserted.
     */
    private record Stored(List<Batch.Sighting> services, List<Tingle> tingles, int newTraces) {
    }

    /**
     * One flush through a pooled connection. It returns once the transaction has
     * committed: a failure to hand the connection back after that loses nothing,
     * so it is not reported as a failed write.
     *
     * @return what the flush stored that the live stream announces
     */
    private Stored writeThroughPool(List<Batch> batches) throws SQLException {
        Connection connection = sql.connection();
        try {
            return writeInTransaction(connection, batches);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "Spider Sense could not return a connection: " + e.getMessage());
            }
        }
    }

    /**
     * One flush as one transaction on {@code connection}, pooled or not, which stays open.
     *
     * @return the services whose rows the flush inserted and the tingles it stored, the
     *         only ones it publishes
     */
    private Stored writeInTransaction(Connection connection, List<Batch> batches) throws SQLException {
        return Sql.inTransaction(connection, c -> {
            Set<String> touched = insertSpans(c, batches);
            insertLogs(c, batches);
            List<Tingle> tingles = insertTingles(c, batches);
            mergeCatalogs(c, batches);
            List<Batch.Sighting> services = mergeServices(c, batches);
            writeMetrics(c, batches);
            int newTraces = traces.merge(c, touched);
            return new Stored(services, tingles, newTraces);
        });
    }

    /**
     * After the commit, so a reader that answers an event by asking the store finds what
     * the event is about: a {@code service} event the service's row, which is what
     * {@code /api/services} lists.
     */
    private void publish(Stored stored) {
        long now = clock.getAsLong();
        events.publishAll("service", stored.services());
        events.publishAll("tingle", stored.tingles());
        // After the tingles, as the ingest is: a stats frame that counts these traces follows them.
        events.tracesIngested(stored.newTraces());
        events.ingested(now);
    }

    // --- spans ---

    Set<String> insertSpans(Connection connection, List<Batch> batches) throws SQLException {
        Set<String> touched = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(SpanRow.MERGE)) {
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

    /**
     * The tingles of a flush, less those whose span and kind are stored, returning the
     * ones it stored (storage.adoc#writer).
     *
     * <p>An exporter sends an export again when the answer to the first was lost, and
     * the copy's spans raise the same tingles. The statements of a batch run in order,
     * so a copy in the same flush finds the row its first copy wrote.
     */
    private static List<Tingle> insertTingles(Connection connection, List<Batch> batches) throws SQLException {
        List<Tingle> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(TingleRow.INSERT)) {
            for (Batch batch : batches) {
                for (Tingle tingle : batch.tingles()) {
                    TingleRow.of(tingle).bind(statement);
                    statement.addBatch();
                    pending.add(tingle);
                }
            }
            if (pending.isEmpty()) {
                return List.of();
            }
            int[] counts = statement.executeBatch();
            List<Tingle> stored = new ArrayList<>(pending.size());
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    stored.add(pending.get(i));
                }
            }
            return stored;
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
     *
     * @return the sightings of the services that had no row, whose rows this inserted
     */
    private List<Batch.Sighting> mergeServices(Connection connection, List<Batch> batches) throws SQLException {
        Map<String, Batch.Sighting> sightings = new LinkedHashMap<>();
        for (Batch batch : batches) {
            for (Batch.Sighting sighting : batch.services()) {
                sightings.put(sighting.name(), sighting);
            }
        }
        List<Batch.Sighting> inserted = new ArrayList<>();
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
            inserted.add(sighting);
        }
        return inserted;
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
     *
     * <p>A process whose mark this writer has found stored is not new either, once
     * the mark is gone: the retention and {@code DELETE /api/data} spare only each
     * service's newest start mark, so the older of two processes sharing the name
     * loses its own, and a mark written again at its next export would move
     * {@code since=start} to that moment. Only a found mark is remembered, never one
     * this flush inserts, since the flush may yet roll back.
     *
     * <p>That memory ends with the writer, so a restart of the standalone Spider Sense
     * both processes send to would forget it. The process of the service's last
     * export is not new after one either: the service row keeps its pid, and the
     * flush that wrote the row marked it ({@link #lastExportedBy}).
     */
    private void markRestart(Connection connection, ServiceRow service, long at) throws SQLException {
        Long pid = service.pid();
        if (pid == null) {
            return;
        }
        MarkRow mark = new MarkRow(at, Marks.START, service.name(), "pid " + pid);
        String marked = mark.storedService() + "\0" + mark.note();
        if (markedStarts.contains(marked)) {
            return;
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM mark WHERE name = ? AND service = ? AND note = ?")) {
            select.setString(1, mark.name());
            select.setString(2, mark.service());
            select.setString(3, mark.note());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    markedStarts.add(marked);
                    return;
                }
            }
        }
        if (lastExportedBy(connection, service.name(), pid)) {
            markedStarts.add(marked);
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(MarkRow.INSERT)) {
            mark.bind(insert);
            insert.executeUpdate();
        }
    }

    /**
     * Whether the stored row of {@code service} names {@code pid}, the process of its
     * last export, while the service has a start mark: the flush that wrote that pid
     * into the row marked the process, whatever became of the mark since. A service
     * with no start mark at all gets one, since {@code since=start} needs it, which also
     * covers a row an import wrote with the pid of a process on another machine.
     */
    private static boolean lastExportedBy(Connection connection, String service, long pid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM service s WHERE s.name = ? AND s.pid = ?"
                        + " AND EXISTS (SELECT 1 FROM mark m WHERE m.name = ? AND m.service = s.name)")) {
            select.setString(1, service);
            select.setLong(2, pid);
            select.setString(3, Marks.START);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
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
            // Cut as JSON before it is hashed, so the stored text reads back and the
            // hash is of what is stored, which is what an import looks the series up by.
            String attributes = AttrJson.encodeSorted(sample.attributes(), Columns.SERIES_ATTRIBUTES);
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
            flushQueued();
            deleteAll.run();
            seriesIds.clear();
        }
    }
}
