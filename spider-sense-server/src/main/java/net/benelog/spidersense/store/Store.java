package net.benelog.spidersense.store;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * The data layer behind one object: the database, the write-behind writer, the
 * retention sweeper, the service registry, the tingle rules and the event bus.
 *
 * <p>It exists so the ingest path has one place to hand batches to and the API
 * has one place to read from. Everything it holds stays separately testable; this
 * class only wires them and says what "a service was seen" means.
 */
public final class Store implements AutoCloseable {

    private final Database database;
    private final Sql sql;
    private final EventBus events = new EventBus();
    private final Tingles tingles;
    private final ServiceRegistry services;
    private final Marks marks;
    private final Acks acks;
    private final Writer writer;
    private final Sweeper sweeper;
    private final IngestCap ingestCap;

    /** The store with the ignore list at its documented default. */
    public Store(String jdbcUrl, @Nullable Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, @Nullable String embeddedService) {
        this(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs, embeddedService,
                IgnoredEndpoints.DEFAULT);
    }

    /** The store with the span cap at its default and no ingest cap. */
    public Store(String jdbcUrl, @Nullable Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, @Nullable String embeddedService,
            @Nullable String ignoreEndpoints) {
        this(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs, embeddedService,
                ignoreEndpoints, Sweeper.DEFAULT_RETENTION_SPANS, IngestCap.none());
    }

    /**
     * @param retentionSpans the most {@code span} rows the sweeper keeps, {@code 0} for no cap
     * @param ingestCap      what decides whether a span is written at all
     *                       (storage.adoc#ingest-cap); {@link IngestCap#none()} accepts everything
     */
    public Store(String jdbcUrl, @Nullable Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, @Nullable String embeddedService,
            @Nullable String ignoreEndpoints,
            long retentionSpans, IngestCap ingestCap) {
        this(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs, embeddedService,
                ignoreEndpoints, retentionSpans, ingestCap, System::currentTimeMillis);
    }

    /**
     * @param clock what the marks, the acknowledgements, the writer's events and the
     *              retention read the time from, in epoch milliseconds
     */
    public Store(String jdbcUrl, @Nullable Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, @Nullable String embeddedService,
            @Nullable String ignoreEndpoints,
            long retentionSpans, IngestCap ingestCap, LongSupplier clock) {
        this.database = Database.open(jdbcUrl, databaseFile);
        this.sql = database.sql();
        this.tingles = new Tingles(slowRequestMs, slowQueryMs, IgnoredEndpoints.of(ignoreEndpoints));
        this.services = new ServiceRegistry(sql, embeddedService);
        this.marks = new Marks(sql, clock);
        this.acks = new Acks(sql, clock);
        this.ingestCap = ingestCap;
        this.writer = new Writer(sql, events, tingles, clock).start(database);
        this.sweeper = new Sweeper(sql, retentionHours, retentionSpans, clock).start();
    }

    public Sql sql() {
        return sql;
    }

    public Database database() {
        return database;
    }

    public EventBus events() {
        return events;
    }

    public Tingles tingles() {
        return tingles;
    }

    public ServiceRegistry services() {
        return services;
    }

    /** Named moments, see marks-and-compare.adoc#marks. */
    public Marks marks() {
        return marks;
    }

    /** Findings a reader has accepted, see findings.adoc#acknowledgements. */
    public Acks acks() {
        return acks;
    }

    public Writer writer() {
        return writer;
    }

    /** {@code POST /api/import}: an exported session document, written back (cli.adoc#export-import). */
    public Importer importer() {
        return new Importer(sql, tingles);
    }

    public Sweeper sweeper() {
        return sweeper;
    }

    /** The per-second span cap the trace decoder asks before it adds a span to a batch. */
    public IngestCap ingestCap() {
        return ingestCap;
    }

    /** {@code /api/status.storage.droppedSpans}: what the ingest cap has turned away. */
    public long droppedSpans() {
        return ingestCap.droppedSpans();
    }

    /** Records the sighting in {@code batch} and pushes a {@code service} event the first time. */
    public void sawService(Batch batch, String name, Map<String, Object> resource, long at) {
        batch.saw(new Batch.Sighting(name, resource, at));
        if (services.seen(name, resource)) {
            events.publish("service", new ServiceInfo(name, resource, at, at, services.isEmbedded(name)));
        }
    }

    public void submit(Batch batch) {
        writer.submit(batch);
    }

    /**
     * {@code DELETE /api/data}: every span, trace, log, metric point, tingle,
     * mark, acknowledgement and catalog row.
     *
     * <p>Through the writer, which flushes what is queued first and flushes
     * nothing else until the deletes have committed.
     */
    public void clear() {
        writer.clear(database::deleteAll);
    }

    @Override
    public void close() {
        sweeper.close();
        writer.close();
        database.close();
    }
}
