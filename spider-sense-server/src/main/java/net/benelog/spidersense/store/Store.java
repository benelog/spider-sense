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
    private final LongSupplier clock;
    private final Sql sql;
    private final EventBus events = new EventBus();
    private final Tingles tingles;
    private final ServiceRegistry services;
    private final Marks marks;
    private final Acks acks;
    private final Writer writer;
    private final Sweeper sweeper;
    private final IngestCap ingestCap;

    /**
     * What a store is opened with, by name, so no two adjacent numbers can be
     * swapped at a call site.
     *
     * @param databaseFile    the file behind {@code jdbcUrl}, or null for a memory database
     * @param retentionHours  how old a row the sweeper keeps (storage.adoc#retention)
     * @param embeddedService the {@code service.name} of the JVM the server runs inside, or null
     * @param ignoreEndpoints the {@code spidersense.ignore.endpoints} globs, or null for none
     * @param retentionSpans  the most {@code span} rows the sweeper keeps, {@code 0} for no cap
     * @param ingestCap       what decides whether a span is written at all
     *                        (storage.adoc#ingest-cap); {@link IngestCap#none()} accepts everything
     * @param clock           what the marks, the acknowledgements, the writer's events and the
     *                        retention read the time from, in epoch milliseconds
     */
    public record Settings(String jdbcUrl, @Nullable Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, @Nullable String embeddedService,
            @Nullable String ignoreEndpoints, long retentionSpans, IngestCap ingestCap, LongSupplier clock) {

        /**
         * The documented defaults on {@code jdbcUrl}: a day of retention, 500 ms and
         * 100 ms thresholds, the default ignore list and span cap, no ingest cap, no
         * embedded service, and the wall clock.
         */
        public static Settings defaults(String jdbcUrl) {
            return new Settings(jdbcUrl, null, 24, 500, 100, null, IgnoredEndpoints.DEFAULT,
                    Sweeper.DEFAULT_RETENTION_SPANS, IngestCap.none(), System::currentTimeMillis);
        }

        public Settings withRetentionSpans(long spans) {
            return new Settings(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs,
                    embeddedService, ignoreEndpoints, spans, ingestCap, clock);
        }

        public Settings withIngestCap(IngestCap cap) {
            return new Settings(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs,
                    embeddedService, ignoreEndpoints, retentionSpans, cap, clock);
        }

        public Settings withClock(LongSupplier time) {
            return new Settings(jdbcUrl, databaseFile, retentionHours, slowRequestMs, slowQueryMs,
                    embeddedService, ignoreEndpoints, retentionSpans, ingestCap, time);
        }
    }

    public Store(Settings settings) {
        this.clock = settings.clock();
        this.database = Database.open(settings.jdbcUrl(), settings.databaseFile());
        this.sql = database.sql();
        this.tingles = new Tingles(settings.slowRequestMs(), settings.slowQueryMs(),
                IgnoredEndpoints.of(settings.ignoreEndpoints()));
        this.services = new ServiceRegistry(sql, settings.embeddedService());
        this.marks = new Marks(sql, clock);
        this.acks = new Acks(sql, clock);
        this.ingestCap = settings.ingestCap();
        this.writer = new Writer(sql, events, tingles, clock).start(database);
        this.sweeper = new Sweeper(sql, settings.retentionHours(), settings.retentionSpans(), clock).start();
    }

    public Sql sql() {
        return sql;
    }

    /** What this store reads the time from, which every reader of it shares. */
    public LongSupplier clock() {
        return clock;
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
        if (services.recordSighting(name, resource)) {
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
