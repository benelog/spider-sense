package net.benelog.spidersense.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.CodeFrames;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.IgnoredEndpoints;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Tingles;
import net.benelog.spidersilk.json.Json;

/**
 * Every answer the agent interface gives, as data rather than as a response.
 *
 * <p>There are two callers and they must never drift: the HTTP handlers, and the
 * CLI, which answers from the H2 file in process when no server is running
 * (agent.md). So a method here takes plain parameters, asks the same
 * {@link Queries} the UI asks, and hands back both renderings of the one result —
 * the JSON of api.md and the Markdown of {@link Text}. The handler picks; nobody
 * computes an answer twice.
 *
 * <p>{@link #readOnly(Config)} is the CLI's way in: it opens the database and
 * builds the queries without a writer and without a sweeper, because a command
 * that prints a table must not start collecting or deleting anything.
 */
public final class Reports implements AutoCloseable {

    /** One answer, in both renderings. */
    public record Report(Json.JsonValue json, String text) {
    }

    private final Config config;
    private final Database database;
    private final boolean ownsDatabase;
    private final Store store;
    private final IntSupplier port;

    private final Queries queries;
    private final MetricQueries metrics;
    private final Marks marks;
    private final Tingles tingles;
    private final ServiceRegistry services;
    private final CodeFrames frames;
    private final Findings findings;
    private final Compare compare;
    private final Check check;
    private final Selectors selectors;
    private final ReadOnlyQuery readOnly;

    /** The server's way: everything is already open, and the writer's counters exist. */
    public Reports(Config config, Store store, IntSupplier port) {
        this(config, store.database(), store, port, store.tingles(), store.services(), store.marks(),
                false);
    }

    /**
     * The CLI's way: the database opened for the length of one command.
     *
     * <p>No writer and no sweeper. {@code AUTO_SERVER=TRUE} means this either joins
     * the running Spider Sense's H2 or opens the file itself, so the answer is the
     * same one the server would have given (storage.md).
     */
    public static Reports readOnly(Config config) {
        Database database = Database.openExisting(config.jdbcUrl(), config.databaseFile());
        Sql sql = database.sql();
        return new Reports(config, database, null, config::port,
                new Tingles(config.slowRequestMs(), config.slowQueryMs(),
                        IgnoredEndpoints.of(config.ignoreEndpoints())),
                new ServiceRegistry(sql, config.embeddedService()), new Marks(sql), true);
    }

    private Reports(Config config, Database database, Store store, IntSupplier port, Tingles tingles,
            ServiceRegistry services, Marks marks, boolean ownsDatabase) {
        this.config = config;
        this.database = database;
        this.store = store;
        this.port = port;
        this.ownsDatabase = ownsDatabase;
        this.tingles = tingles;
        this.services = services;
        this.marks = marks;
        Sql sql = database.sql();
        this.queries = new Queries(sql, tingles, services);
        this.metrics = new MetricQueries(sql);
        this.frames = new CodeFrames(config.appPackages());
        this.findings = new Findings(sql, queries, metrics, services, tingles, frames);
        this.compare = new Compare(queries);
        this.check = new Check(queries, findings, tingles);
        this.selectors = new Selectors(marks);
        this.readOnly = new ReadOnlyQuery(database);
    }

    // --- what the callers need beside the answers -----------------------------

    public Config config() {
        return config;
    }

    public Selectors selectors() {
        return selectors;
    }

    public Tingles thresholds() {
        return tingles;
    }

    public Marks markStore() {
        return marks;
    }

    /** The base URL an empty answer tells the caller to send telemetry to. */
    public String endpoint() {
        return config.endpoint(port.getAsInt());
    }

    // --- status ---------------------------------------------------------------

    /**
     * What is running, where the database is, how much it holds.
     *
     * <p>Mode, endpoint and start are passed in rather than read from the config,
     * because in the CLI's read-only mode there is no server: the mode is
     * {@code file} and there is no endpoint to advertise.
     */
    public Report status(String mode, String endpoint, long startedAt) {
        Database.Storage storage = database.storage();
        Json.JsonObject otlp = Json.obj();
        if (endpoint != null) {
            otlp.put("traces", endpoint + "/v1/traces")
                    .put("metrics", endpoint + "/v1/metrics")
                    .put("logs", endpoint + "/v1/logs");
        }
        Json.JsonObject json = Json.obj()
                .put("name", ApiRoutes.NAME)
                .put("version", ApiRoutes.VERSION)
                .put("mode", mode)
                .put("startedAt", startedAt)
                .put("now", System.currentTimeMillis())
                .put("endpoint", endpoint)
                .put("otlp", otlp)
                .put("embeddedService", services.embeddedService())
                .put("thresholds", Json.obj()
                        .put("slowRequestMs", tingles.slowRequestMs())
                        .put("slowQueryMs", tingles.slowQueryMs())
                        .put("responseBucketsMs", Codecs.longs(queries.responseBuckets().bounds())))
                .put("ignore", Json.obj()
                        .put("endpoints", Codecs.strings(tingles.ignored().patterns())))
                .put("retention", Json.obj().put("hours", config.retentionHours()))
                .put("storage", Json.obj()
                        .put("url", storage.url())
                        .put("path", storage.path())
                        .put("sizeBytes", storage.sizeBytes())
                        .put("fallback", storage.fallback())
                        .put("fallbackReason", storage.fallbackReason())
                        .put("droppedBatches", store == null ? 0 : store.writer().droppedBatches())
                        .put("queued", store == null ? 0 : store.writer().queued()))
                .put("counts", Json.obj()
                        .put("spans", queries.spanCount())
                        .put("traces", queries.traceCount())
                        .put("logs", queries.logCount())
                        .put("metricSeries", queries.metricSeriesCount())
                        .put("services", services.count()))
                .put("oldest", Json.obj()
                        .put("span", queries.oldestSpan())
                        .put("log", queries.oldestLog()));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", ApiRoutes.NAME + " " + ApiRoutes.VERSION);
        fields.put("mode", mode);
        fields.put("endpoint", endpoint);
        fields.put("started", startedAt <= 0 ? null : Text.instantMillis(startedAt));
        fields.put("embedded service", services.embeddedService());
        fields.put("thresholds", "slow request " + tingles.slowRequestMs() + " ms, slow query "
                + tingles.slowQueryMs() + " ms");
        fields.put("ignore", tingles.ignored().isEmpty() ? "none"
                : String.join(", ", tingles.ignored().patterns()));
        fields.put("retention", config.retentionHours() + " hours");
        fields.put("database", storage.path() == null ? storage.url() : storage.path());
        fields.put("database size", storage.sizeBytes() + " bytes");
        if (storage.fallback()) {
            fields.put("fallback", storage.fallbackReason());
        }
        fields.put("spans", String.valueOf(queries.spanCount()));
        fields.put("traces", String.valueOf(queries.traceCount()));
        fields.put("logs", String.valueOf(queries.logCount()));
        fields.put("metric series", String.valueOf(queries.metricSeriesCount()));
        fields.put("services", String.valueOf(services.count()));
        long oldest = queries.oldestSpan();
        fields.put("oldest span", oldest <= 0 ? null : Text.instantMillis(oldest));
        return new Report(json, Text.status(fields));
    }

    // --- findings, marks, compare, check --------------------------------------

    public Report findings(Window window, String service, int limit, boolean full) {
        List<Findings.Finding> found = findings.findings(window, service, limit);
        long requests = queries.totals(window, service).requests();
        Json.JsonObject json = Json.obj()
                .put("window", Codecs.window(window))
                .put("requests", requests)
                .put("findings", Codecs.findings(found));
        return new Report(json, Text.findings(window, service, requests, found, full, endpoint()));
    }

    public Report marks(int limit) {
        List<Marks.Mark> list = marks.list(limit);
        return new Report(Json.obj().put("marks", Codecs.marks(list)), Text.marks(list));
    }

    /** Records a mark, which the CLI must be able to do with no server running. */
    public Marks.Mark mark(String name, String note, String service) {
        return marks.create(name, service, note, null);
    }

    public Report mark(Marks.Mark mark) {
        return new Report(Codecs.mark(mark), Text.mark(mark));
    }

    /**
     * The two windows side by side.
     *
     * <p>{@code before} is {@code [before, after)} and {@code after} is
     * {@code [after, until)}: the instant a mark names belongs to the window that
     * starts with it, so exercising, marking and exercising again gives two windows
     * that do not share a request.
     */
    public Report compare(long before, long after, long until, String service, boolean full) {
        Window first = Window.of(before, Math.max(before, after - 1));
        Window second = Window.of(after, Math.max(after, until));
        Compare.Comparison comparison = compare.compare(first, second, service);
        return new Report(Codecs.comparison(comparison), Text.compare(comparison, service, full));
    }

    public Report check(Window window, String service, String endpoint, Map<String, Double> rules) {
        Check.CheckResult result = check.check(window, service, endpoint, rules);
        return new Report(Codecs.checkResult(result), Text.check(result, window, service, endpoint));
    }

    /** The rule set a {@code check} with no rule uses. */
    public Map<String, Double> defaultRules() {
        return check.defaults();
    }

    // --- the lists the UI also serves ------------------------------------------

    public Report traces(Queries.TraceFilter filter, boolean full) {
        List<Stats.TraceSummary> traces = queries.traces(filter);
        long total = queries.traceTotal(filter);
        Json.JsonObject json = Json.obj()
                .put("traces", Codecs.traceSummaries(traces))
                .put("total", total)
                .put("window", Codecs.window(filter.window()));
        long requests = queries.totals(filter.window(), filter.service()).requests();
        return new Report(json, Text.traces(filter.window(), filter.service(), traces, total, requests,
                endpoint()));
    }

    /** One trace, or null when the id is not stored. */
    public Report trace(String traceId, boolean full) {
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace == null) {
            return null;
        }
        return new Report(Codecs.trace(trace, tingles), Text.trace(trace, tingles, frames, full));
    }

    public Report endpoints(Window window, String service) {
        List<Stats.EndpointStats> endpoints = queries.endpoints(window, service, null);
        long requests = queries.totals(window, service).requests();
        return new Report(Json.obj().put("endpoints", Codecs.endpoints(endpoints)),
                Text.endpoints(window, service, endpoints, requests, endpoint()));
    }

    public Report queries(Window window, String service, String sort, int limit, boolean full) {
        List<Stats.QueryStats> list = queries.queries(window, service, sort, limit, null);
        long requests = queries.totals(window, service).requests();
        return new Report(Json.obj().put("queries", Codecs.queries(list)),
                Text.queries(window, service, list, requests, full, endpoint()));
    }

    public Report errors(Window window, String service, int limit, boolean full) {
        List<Stats.ErrorGroup> list = queries.errors(window, service, limit, null);
        long requests = queries.totals(window, service).requests();
        return new Report(Json.obj().put("errors", Codecs.errorGroups(list)),
                Text.errors(window, service, list, requests, full, frames, endpoint()));
    }

    public Report logs(Queries.LogFilter filter) {
        List<net.benelog.spidersense.store.LogRecord> logs = queries.logs(filter);
        long total = queries.logTotal(filter);
        Json.JsonObject json = Json.obj()
                .put("logs", Codecs.logs(logs))
                .put("total", total);
        return new Report(json, Text.logs(filter.window(), filter.service(), logs, total, endpoint()));
    }

    /**
     * One read-only statement over the schema of storage.md: the question findings
     * cannot answer (agent.md).
     *
     * <p>It goes through here like every other answer, and for the same reason:
     * the CLI's direct-file path must give the same rows as the HTTP one, down to
     * the row cap and the truncation notice.
     *
     * @throws ReadOnlyQuery.Refused when the statement is not one this may run, or
     *                               when H2 refuses it
     * @throws IllegalStateException when the database has no reader user yet
     */
    public Report sql(String statement, int limit, boolean full) {
        ReadOnlyQuery.Result result = readOnly.run(statement, limit);
        return new Report(Codecs.sqlResult(result), Text.sql(result, limit, full));
    }

    public Report services(Window window) {
        List<Stats.ServiceSummary> summaries = queries.services(window);
        long requests = queries.totals(window, null).requests();
        return new Report(Json.obj().put("services", Codecs.serviceSummaries(summaries)),
                Text.services(window, summaries, requests, endpoint()));
    }

    @Override
    public void close() {
        if (ownsDatabase) {
            database.close();
        }
    }
}
