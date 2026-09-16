package net.benelog.spidersense.store;

import java.nio.file.Path;
import java.util.Map;

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
    private final Writer writer;
    private final Sweeper sweeper;

    public Store(String jdbcUrl, Path databaseFile, int retentionHours,
            long slowRequestMs, long slowQueryMs, String embeddedService) {
        this.database = Database.open(jdbcUrl, databaseFile);
        this.sql = database.sql();
        this.tingles = new Tingles(slowRequestMs, slowQueryMs);
        this.services = new ServiceRegistry(sql, embeddedService);
        this.writer = new Writer(sql, events, tingles).start();
        this.sweeper = new Sweeper(sql, retentionHours).start();
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

    public Writer writer() {
        return writer;
    }

    public Sweeper sweeper() {
        return sweeper;
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

    /** {@code DELETE /api/data}: every span, trace, log, metric point and tingle. */
    public void clear() {
        writer.flushNow();
        database.deleteAll();
        writer.forgetSeriesIds();
    }

    @Override
    public void close() {
        sweeper.close();
        writer.close();
        database.close();
    }
}
