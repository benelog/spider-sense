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

    private static final System.Logger LOG = System.getLogger(Writer.class.getName());

    private final BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Long> seriesIds = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Object flushLock = new Object();
    private final Object wakeUp = new Object();

    private final Sql sql;
    private final EventBus events;
    private final Tingles tingles;
    private final Thread thread;

    private final AtomicInteger queuedRecords = new AtomicInteger();

    private volatile boolean running = true;

    public Writer(Sql sql, EventBus events, Tingles tingles) {
        this.sql = sql;
        this.events = events;
        this.tingles = tingles;
        this.thread = new Thread(this::loop, "spider-sense-writer");
        // Daemon: in agent mode the monitored application's main must be able to return.
        this.thread.setDaemon(true);
    }

    public Writer start() {
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "spider-sense-writer-shutdown"));
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
            dropped.incrementAndGet();
        }
        int queued = queuedRecords.addAndGet(batch.records());
        if (queued >= FLUSH_RECORDS) {
            synchronized (wakeUp) {
                wakeUp.notifyAll();
            }
        }
    }

    public long droppedBatches() {
        return dropped.get();
    }

    public int queued() {
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
            flush();
        }
        flush();
    }

    private void flush() {
        synchronized (flushLock) {
            List<Batch> batches = new ArrayList<>();
            queue.drainTo(batches);
            queuedRecords.set(0);
            if (batches.isEmpty()) {
                return;
            }
            try {
                write(batches);
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Spider Sense could not store a batch", e);
                return;
            }
            publish(batches);
        }
    }

    private void write(List<Batch> batches) throws SQLException {
        try (Connection connection = sql.connection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> touched = insertSpans(connection, batches);
                insertLogs(connection, batches);
                insertTingles(connection, batches);
                mergeCatalogs(connection, batches);
                mergeServices(connection, batches);
                insertMetrics(connection, batches);
                mergeTraces(connection, touched);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void publish(List<Batch> batches) {
        long now = System.currentTimeMillis();
        for (Batch batch : batches) {
            for (Tingle tingle : batch.tingles()) {
                events.publish("tingle", tingle);
            }
        }
        events.ingested(now);
    }

    // --- spans ---

    static final String INSERT_SPAN = """
            INSERT INTO span (trace_id, span_id, parent_span_id, service, name, kind, start_ms, start_ns,
                duration_ns, status, status_message, entry, error, slow, category, endpoint, endpoint_id,
                http_method, http_route, http_status, db_system, db_statement, db_namespace, db_operation,
                db_table, query_id, error_type, error_message, error_id, scope, attributes, events)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private Set<String> insertSpans(Connection connection, List<Batch> batches) throws SQLException {
        Set<String> touched = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SPAN)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (SpanRecord span : batch.spans()) {
                    bindSpan(statement, span);
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

    private void bindSpan(PreparedStatement statement, SpanRecord span) throws SQLException {
        boolean entry = tingles.isEntry(span);
        boolean error = span.isError();
        String statementText = span.dbStatement();
        String endpoint = entry ? span.endpointName() : null;
        String errorType = error ? span.errorType() : null;
        String errorMessage = error ? span.errorMessage() : null;

        int i = 1;
        statement.setString(i++, span.traceId());
        statement.setString(i++, span.spanId());
        statement.setString(i++, span.parentSpanId());
        statement.setString(i++, span.service());
        statement.setString(i++, cut(span.name(), 1024));
        statement.setString(i++, span.kind());
        statement.setLong(i++, span.startMillis());
        statement.setLong(i++, span.startNanos());
        statement.setLong(i++, span.durationNanos());
        statement.setString(i++, span.status());
        statement.setString(i++, cut(span.statusMessage(), 4096));
        statement.setBoolean(i++, entry);
        statement.setBoolean(i++, error);
        statement.setBoolean(i++, tingles.isSlow(span));
        statement.setString(i++, span.category());
        statement.setString(i++, cut(endpoint, 1024));
        statement.setString(i++, endpoint == null ? null : Ids.endpointId(span.service(), endpoint));
        statement.setString(i++, cut(span.httpMethod(), 16));
        statement.setString(i++, cut(span.httpRoute(), 1024));
        setLong(statement, i++, span.httpStatus());
        statement.setString(i++, cut(span.dbSystem(), 64));
        statement.setString(i++, statementText);
        statement.setString(i++, cut(span.dbNamespace(), 255));
        statement.setString(i++, cut(span.dbOperation(), 64));
        statement.setString(i++, cut(span.dbTable(), 255));
        statement.setString(i++, statementText == null ? null
                : Ids.queryId(span.service(), String.valueOf(span.dbSystem()), statementText));
        statement.setString(i++, cut(errorType, 512));
        statement.setString(i++, cut(errorMessage, 4096));
        statement.setString(i++, error
                ? Ids.errorId(span.service(), String.valueOf(errorType), errorMessage, span.stacktrace())
                : null);
        statement.setString(i++, cut(span.scope(), 255));
        statement.setString(i++, AttrJson.encode(span.attributes(), 65535));
        statement.setString(i, AttrJson.encodeEvents(span.events(), 65535));
    }

    // --- traces ---

    private static final String MERGE_TRACE = """
            MERGE INTO trace (trace_id, start_ms, end_ms, duration_ns, root_span_id, root_name, root_service,
                root_kind, services, span_count, error_count, db_count, http_status, slow, error)
            KEY(trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /** One row per span of the touched traces, enough to rebuild the summaries. */
    private record TraceSpan(String traceId, String spanId, @Nullable String parentSpanId,
            String service, String name, @Nullable String endpoint, String kind, long startMs,
            long startNs, long durationNs, boolean error, boolean isDb, @Nullable Long httpStatus) {
    }

    void mergeTraces(Connection connection, Set<String> traceIds) throws SQLException {
        if (traceIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(traceIds);
        Map<String, List<TraceSpan>> byTrace = new LinkedHashMap<>();
        String select = """
                SELECT trace_id, span_id, parent_span_id, service, name, endpoint, kind, start_ms, start_ns,
                       duration_ns, error, db_statement, http_status
                FROM span WHERE trace_id IN (""" + Sql.placeholders(ids.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setString(i + 1, ids.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TraceSpan span = new TraceSpan(rs.getString("trace_id"), rs.getString("span_id"),
                            rs.getString("parent_span_id"), rs.getString("service"), rs.getString("name"),
                            rs.getString("endpoint"), rs.getString("kind"), rs.getLong("start_ms"),
                            rs.getLong("start_ns"), rs.getLong("duration_ns"), rs.getBoolean("error"),
                            rs.getString("db_statement") != null, Sql.longOrNull(rs, "http_status"));
                    byTrace.computeIfAbsent(span.traceId(), id -> new ArrayList<>()).add(span);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(MERGE_TRACE)) {
            for (var entry : byTrace.entrySet()) {
                bindTrace(statement, entry.getKey(), entry.getValue());
                statement.addBatch();
            }
            if (!byTrace.isEmpty()) {
                statement.executeBatch();
            }
        }
    }

    private void bindTrace(PreparedStatement statement, String traceId, List<TraceSpan> spans)
            throws SQLException {
        Set<String> spanIds = new HashSet<>();
        for (TraceSpan span : spans) {
            spanIds.add(span.spanId());
        }
        long startMs = Long.MAX_VALUE;
        long endMs = Long.MIN_VALUE;
        long startNs = Long.MAX_VALUE;
        long endNs = Long.MIN_VALUE;
        int errorCount = 0;
        int dbCount = 0;
        Set<String> services = new LinkedHashSet<>();
        TraceSpan root = null;
        for (TraceSpan span : spans) {
            startMs = Math.min(startMs, span.startMs());
            endMs = Math.max(endMs, span.startMs() + span.durationNs() / 1_000_000L);
            startNs = Math.min(startNs, span.startNs());
            endNs = Math.max(endNs, span.startNs() + span.durationNs());
            services.add(span.service());
            if (span.error()) {
                errorCount++;
            }
            if (span.isDb()) {
                dbCount++;
            }
            boolean isRoot = span.parentSpanId() == null || !spanIds.contains(span.parentSpanId());
            if (isRoot && (root == null || span.startNs() < root.startNs())) {
                root = span;
            }
        }
        if (root == null) {
            root = spans.get(0);
        }
        long durationNs = Math.max(0, endNs - startNs);
        double durationMs = durationNs / 1_000_000.0;
        String rootName = root.endpoint() != null ? root.endpoint() : root.name();

        int i = 1;
        statement.setString(i++, traceId);
        statement.setLong(i++, startMs);
        statement.setLong(i++, endMs);
        statement.setLong(i++, durationNs);
        statement.setString(i++, root.spanId());
        statement.setString(i++, cut(rootName, 1024));
        statement.setString(i++, root.service());
        statement.setString(i++, root.kind());
        statement.setString(i++, AttrJson.encodeStrings(List.copyOf(services)));
        statement.setInt(i++, spans.size());
        statement.setInt(i++, errorCount);
        statement.setInt(i++, dbCount);
        setLong(statement, i++, root.httpStatus());
        statement.setBoolean(i++, durationMs > tingles.slowRequestMs());
        statement.setBoolean(i, errorCount > 0);
    }

    // --- logs, tingles, catalogs, services, metrics ---

    static final String INSERT_LOG = """
            INSERT INTO log (at_ms, service, severity_number, severity, body, logger, trace_id, span_id,
                attributes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private void insertLogs(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LOG)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (LogRecord log : batch.logs()) {
                    int i = 1;
                    statement.setLong(i++, log.at());
                    statement.setString(i++, log.service());
                    statement.setInt(i++, log.severityNumber());
                    statement.setString(i++, log.severity());
                    statement.setString(i++, cut(log.body(), 65535));
                    statement.setString(i++, cut(log.logger(), 512));
                    statement.setString(i++, log.traceId());
                    statement.setString(i++, log.spanId());
                    statement.setString(i, AttrJson.encode(log.attributes(), 65535));
                    statement.addBatch();
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    static final String INSERT_TINGLE = """
            INSERT INTO tingle (at_ms, kind, service, title, detail, trace_id, span_id, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private void insertTingles(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TINGLE)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (Tingle tingle : batch.tingles()) {
                    int i = 1;
                    statement.setLong(i++, tingle.at());
                    statement.setString(i++, tingle.kind());
                    statement.setString(i++, tingle.service());
                    statement.setString(i++, cut(tingle.title(), 1024));
                    statement.setString(i++, cut(tingle.detail(), 4096));
                    statement.setString(i++, tingle.traceId());
                    statement.setString(i++, tingle.spanId());
                    statement.setDouble(i, tingle.durationMs());
                    statement.addBatch();
                    pending++;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    static final String MERGE_CATALOG = """
            MERGE INTO db_table (service, schema_name, table_name, product, indexes, seen_ms)
            KEY (service, schema_name, table_name) VALUES (?, ?, ?, ?, ?, ?)""";

    /**
     * The catalog rows of a flush, merged in the flush's own transaction.
     *
     * <p>A merge rather than an insert because the extension reads a table's
     * indexes once per process: the row of a table looked up again after a restart
     * is the newer truth about the same table, and replaces the older one
     * (storage.adoc#writer).
     */
    private void mergeCatalogs(Connection connection, List<Batch> batches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MERGE_CATALOG)) {
            int pending = 0;
            for (Batch batch : batches) {
                for (Batch.Catalog catalog : batch.catalogs()) {
                    int i = 1;
                    statement.setString(i++, cut(catalog.service(), 255));
                    statement.setString(i++, cut(catalog.schemaName(), 255));
                    statement.setString(i++, cut(catalog.table(), 255));
                    statement.setString(i++, cut(catalog.product(), 64));
                    statement.setString(i++, cut(catalog.indexes(), 65535));
                    statement.setLong(i, catalog.at());
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
     * <p>The stored process id is read first, because a sighting whose
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
            Object language = sighting.resource().get("telemetry.sdk.language");
            Object pid = sighting.resource().get("process.pid");
            String resource = AttrJson.encode(sighting.resource(), 65535);
            markRestart(connection, sighting, pid);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE service SET language = ?, pid = ?, last_seen = ?, resource = ? WHERE name = ?")) {
                update.setString(1, language == null ? null : String.valueOf(language));
                setLong(update, 2, pid instanceof Number n ? n.longValue() : null);
                update.setLong(3, sighting.at());
                update.setString(4, resource);
                update.setString(5, sighting.name());
                if (update.executeUpdate() > 0) {
                    continue;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO service (name, language, pid, first_seen, last_seen, resource)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, sighting.name());
                insert.setString(2, language == null ? null : String.valueOf(language));
                setLong(insert, 3, pid instanceof Number n ? n.longValue() : null);
                insert.setLong(4, sighting.at());
                insert.setLong(5, sighting.at());
                insert.setString(6, resource);
                insert.executeUpdate();
            }
        }
    }

    /**
     * A {@code start} mark for a service whose process id is new.
     *
     * <p>"New" is both a service nobody has stored yet and a service whose stored
     * pid differs: the second is the restart an agent has just caused by rebuilding
     * and running the application again. A sighting without a {@code process.pid}
     * marks nothing, since there is nothing to compare.
     */
    private void markRestart(Connection connection, Batch.Sighting sighting, @Nullable Object pid)
            throws SQLException {
        if (!(pid instanceof Number number)) {
            return;
        }
        Long stored = null;
        boolean known = false;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT pid FROM service WHERE name = ?")) {
            select.setString(1, sighting.name());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    known = true;
                    stored = Sql.longOrNull(rs, "pid");
                }
            }
        }
        if (known && stored != null && stored == number.longValue()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO mark (at_ms, name, service, note) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, sighting.at());
            insert.setString(2, Marks.START);
            insert.setString(3, sighting.name());
            insert.setString(4, "pid " + number.longValue());
            insert.executeUpdate();
        }
    }

    static final String MERGE_POINT = """
            MERGE INTO metric_point (series_id, at_ms, value, count, sum, min, max, buckets)
            KEY(series_id, at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private void insertMetrics(Connection connection, List<Batch> batches) throws SQLException {
        Map<String, Batch.MetricSample> metadata = new LinkedHashMap<>();
        List<Batch.MetricSample> samples = new ArrayList<>();
        for (Batch batch : batches) {
            for (Batch.MetricSample sample : batch.metrics()) {
                metadata.put(sample.name(), sample);
                samples.add(sample);
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO metric (name, type, unit, description, monotonic, temporality)"
                        + " KEY(name) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Batch.MetricSample sample : metadata.values()) {
                statement.setString(1, sample.name());
                statement.setString(2, sample.type());
                statement.setString(3, cut(sample.unit(), 64));
                statement.setString(4, cut(sample.description(), 1024));
                statement.setBoolean(5, sample.monotonic());
                statement.setString(6, sample.temporality());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(MERGE_POINT)) {
            for (Batch.MetricSample sample : samples) {
                long seriesId = seriesId(connection, sample);
                MetricPoint point = sample.point();
                int i = 1;
                statement.setLong(i++, seriesId);
                statement.setLong(i++, point.at());
                statement.setDouble(i++, point.value());
                statement.setLong(i++, point.count());
                statement.setDouble(i++, point.sum());
                statement.setDouble(i++, point.min());
                statement.setDouble(i++, point.max());
                statement.setString(i, buckets(point));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static @Nullable String buckets(MetricPoint point) {
        double[] bounds = point.bounds();
        long[] counts = point.bucketCounts();
        if (!point.hasBuckets() || bounds == null || counts == null) {
            return null;
        }
        StringBuilder json = new StringBuilder("{\"bounds\":[");
        for (int i = 0; i < bounds.length; i++) {
            json.append(i == 0 ? "" : ",").append(bounds[i]);
        }
        json.append("],\"counts\":[");
        for (int i = 0; i < counts.length; i++) {
            json.append(i == 0 ? "" : ",").append(counts[i]);
        }
        return json.append("]}").toString();
    }

    /** Series ids are looked up once and cached; a JVM exports the same few hundred forever. */
    private long seriesId(Connection connection, Batch.MetricSample sample) throws SQLException {
        String attributes = AttrJson.encodeSorted(sample.attributes());
        String hash = Ids.shortHash(attributes);
        String key = sample.service() + "\0" + sample.name() + "\0" + hash;
        Long cached = seriesIds.get(key);
        if (cached != null) {
            return cached;
        }
        long id = lookupOrCreateSeries(connection, sample, hash, attributes);
        seriesIds.put(key, id);
        return id;
    }

    private long lookupOrCreateSeries(Connection connection, Batch.MetricSample sample, String hash,
            String attributes) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM metric_series WHERE service = ? AND name = ? AND attr_hash = ?")) {
            select.setString(1, sample.service());
            select.setString(2, sample.name());
            select.setString(3, hash);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO metric_series (service, name, attr_hash, attributes) VALUES (?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, sample.service());
            insert.setString(2, sample.name());
            insert.setString(3, hash);
            insert.setString(4, cut(attributes, 4096));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("No id for metric series " + sample.name());
    }

    /**
     * The other way rows reach these tables: an exported session document, read
     * back through this writer's own insert and {@code trace} merge (cli.adoc#export-import).
     *
     * <p>It is a separate object rather than a method here because an import is
     * not write-behind: it is one transaction on the calling thread, and it must
     * be able to say what it wrote.
     */
    public Importer importer() {
        return new Importer(sql, this);
    }

    /** {@code DELETE /api/data} invalidates the series cache along with the rows. */
    public void forgetSeriesIds() {
        seriesIds.clear();
    }

    static void setLong(PreparedStatement statement, int index, @Nullable Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    static @Nullable String cut(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
