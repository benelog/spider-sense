package net.benelog.spidersense.api;

import java.io.OutputStream;
import java.util.ArrayList;
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
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.IgnoredEndpoints;
import net.benelog.spidersense.store.Importer;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.Sql;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersense.store.Tingles;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * Every answer the agent interface gives, as data rather than as a response.
 *
 * <p>There are two callers and they must never drift: the HTTP handlers, and the
 * CLI, which answers from the H2 file in process when no server is running
 * (agent-loop.adoc#interfaces). So a method here takes plain parameters, asks the same
 * {@link Queries} the UI asks, and hands back both renderings of the one result —
 * the JSON of api.adoc and the Markdown of {@link Text}. The handler picks; nobody
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

    /**
     * One of the two ids a diff was asked for is not stored.
     *
     * <p>A single trace that is missing is a {@code null} report, because there is
     * only one id it could have been; a diff has two, and an answer that did not
     * name which one it could not find would leave the caller to guess. It is a
     * {@code 404} over HTTP and exit code {@code 4} in the CLI (cli.adoc#exit-codes).
     */
    public static final class NoSuchTrace extends RuntimeException {

        private final String traceId;

        public NoSuchTrace(String traceId) {
            super("No such trace: " + traceId);
            this.traceId = traceId;
        }

        public String traceId() {
            return traceId;
        }
    }

    private final Config config;
    private final Database database;
    private final boolean ownsDatabase;
    private final @Nullable Store store;
    private final IntSupplier port;

    private final Queries queries;
    private final MetricQueries metrics;
    private final Marks marks;
    private final Acks acks;
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
     * same one the server would have given (storage.adoc).
     */
    public static Reports readOnly(Config config) {
        Database database = Database.openExisting(config.jdbcUrl(), config.databaseFile());
        Sql sql = database.sql();
        return new Reports(config, database, null, config::port,
                new Tingles(config.slowRequestMs(), config.slowQueryMs(),
                        IgnoredEndpoints.of(config.ignoreEndpoints())),
                new ServiceRegistry(sql, config.embeddedService()), new Marks(sql), true);
    }

    private Reports(Config config, Database database, @Nullable Store store, IntSupplier port,
            Tingles tingles, ServiceRegistry services, Marks marks, boolean ownsDatabase) {
        this.config = config;
        this.database = database;
        this.store = store;
        this.port = port;
        this.ownsDatabase = ownsDatabase;
        this.tingles = tingles;
        this.services = services;
        this.marks = marks;
        Sql sql = database.sql();
        this.acks = new Acks(sql);
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

    /** The application frames of a stack trace, by the rules a finding's {@code code} follows. */
    public CodeFrames codeFrames() {
        return frames;
    }

    public Tingles thresholds() {
        return tingles;
    }

    public Marks markStore() {
        return marks;
    }

    /** Acknowledged findings, which the CLI must be able to write with no server running. */
    public Acks ackStore() {
        return acks;
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
    public Report status(String mode, @Nullable String endpoint, long startedAt) {
        Database.Storage storage = database.storage();
        Json.JsonObject otlp = Json.obj();
        if (endpoint != null) {
            otlp.put("traces", endpoint + "/v1/traces")
                    .put("metrics", endpoint + "/v1/metrics")
                    .put("logs", endpoint + "/v1/logs");
        }
        Json.JsonObject json = Json.obj()
                .put("name", Version.NAME)
                .put("version", Version.CURRENT)
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
                .put("codeFrames", Json.obj()
                        .put("appPackages", Codecs.strings(frames.appPackages()))
                        .put("frameworkPrefixes", Codecs.strings(CodeFrames.FRAMEWORK_PREFIXES)))
                .put("jar", config.jar())
                .put("retention", Json.obj()
                        .put("hours", config.retentionHours())
                        .put("spans", config.retentionSpans()))
                .put("ingest", ingest())
                .put("storage", Json.obj()
                        .put("url", storage.url())
                        .put("path", storage.path())
                        .put("sizeBytes", storage.sizeBytes())
                        .put("fallback", storage.fallback())
                        .put("fallbackReason", storage.fallbackReason())
                        .put("droppedBatches", store == null ? 0 : store.writer().droppedBatches())
                        .put("droppedSpans", droppedSpans())
                        .put("queued", store == null ? 0 : store.writer().queuedBatches()))
                .put("counts", Json.obj()
                        .put("spans", queries.spanCount())
                        .put("traces", queries.traceCount())
                        .put("logs", queries.logCount())
                        .put("metricSeries", queries.metricSeriesCount())
                        .put("services", services.count()))
                .put("oldest", Json.obj()
                        .put("span", queries.oldestSpan())
                        .put("log", queries.oldestLog()));

        Map<String, @Nullable String> fields = new LinkedHashMap<>();
        fields.put("name", Version.NAME + " " + Version.CURRENT);
        fields.put("mode", mode);
        fields.put("endpoint", endpoint);
        fields.put("started", startedAt <= 0 ? null : Text.instantMillis(startedAt));
        fields.put("embedded service", services.embeddedService());
        fields.put("thresholds", "slow request " + tingles.slowRequestMs() + " ms, slow query "
                + tingles.slowQueryMs() + " ms");
        fields.put("ignore", tingles.ignored().isEmpty() ? "none"
                : String.join(", ", tingles.ignored().patterns()));
        fields.put("retention", config.retentionHours() + " hours, " + config.retentionSpans() + " spans");
        if (config.maxSpansPerSecond() != null) {
            fields.put("ingest cap", config.maxSpansPerSecond() + " spans/s");
        }
        fields.put("database", storage.path() == null ? storage.url() : storage.path());
        fields.put("database size", storage.sizeBytes() + " bytes");
        if (storage.fallback()) {
            fields.put("fallback", storage.fallbackReason());
        }
        fields.put("dropped spans", String.valueOf(droppedSpans()));
        fields.put("spans", String.valueOf(queries.spanCount()));
        fields.put("traces", String.valueOf(queries.traceCount()));
        fields.put("logs", String.valueOf(queries.logCount()));
        fields.put("metric series", String.valueOf(queries.metricSeriesCount()));
        fields.put("services", String.valueOf(services.count()));
        long oldest = queries.oldestSpan();
        fields.put("oldest span", oldest <= 0 ? null : Text.instantMillis(oldest));
        return new Report(json, Text.status(fields));
    }

    /**
     * {@code ingest.maxSpansPerSecond}: the configured cap, or JSON null when there is
     * none. The key is always present so the UI can tell "no cap" from "old server".
     */
    private Json.JsonObject ingest() {
        Json.JsonObject json = Json.obj();
        Long cap = config.maxSpansPerSecond();
        if (cap == null) {
            return json.putNull("maxSpansPerSecond");
        }
        return json.put("maxSpansPerSecond", cap.longValue());
    }

    /** Zero without a store: the CLI reads the file, it never received anything itself. */
    private long droppedSpans() {
        return store == null ? 0 : store.droppedSpans();
    }

    // --- findings, marks, compare, check --------------------------------------

    public Report findings(Window window, @Nullable String service, int limit, boolean full) {
        return findings(window, service, limit, full, false);
    }

    /**
     * The same report, narrowed to what a reader has not accepted yet.
     *
     * @param hideAcked whether acknowledged findings are left out rather than ranked last
     */
    public Report findings(Window window, @Nullable String service, int limit, boolean full,
            boolean hideAcked) {
        Findings.Answer answer = findings.answer(window, service, limit, hideAcked);
        List<Findings.Finding> found = answer.findings();
        long requests = queries.totals(window, service).requests();
        Json.JsonObject json = Json.obj()
                .put("window", Codecs.window(window))
                .put("requests", requests)
                .put("acked", answer.acked())
                .put("resolved", answer.resolved())
                .put("findings", Codecs.findings(found));
        return new Report(json, Text.findings(window, service, requests, answer.acked(),
                answer.resolved(), found, full, endpoint()));
    }

    /**
     * One finding, as the list renders it: its row of the table and its evidence
     * block, numbered by its rank among every finding of the window, so the bytes
     * are those {@code findings} prints for it (cli.adoc#one-finding).
     *
     * @return null when the rules do not produce that id over the window
     */
    public @Nullable Report finding(Window window, @Nullable String service, String id,
            boolean full) {
        List<Findings.Finding> ranked =
                findings.answer(window, service, Queries.ALL_GROUPS, false).findings();
        for (int i = 0; i < ranked.size(); i++) {
            Findings.Finding finding = ranked.get(i);
            if (finding.id().equals(id)) {
                long requests = queries.totals(window, service).requests();
                Json.JsonObject json = Json.obj()
                        .put("window", Codecs.window(window))
                        .put("requests", requests)
                        .put("rank", i + 1)
                        .put("finding", Codecs.finding(finding));
                return new Report(json, Text.finding(window, service, requests, i + 1, finding, full));
            }
        }
        return null;
    }

    public Report marks(int limit) {
        List<Marks.Mark> list = marks.list(limit);
        return new Report(Json.obj().put("marks", Codecs.marks(list)), Text.marks(list));
    }

    /** Records a mark, which the CLI must be able to do with no server running. */
    public Marks.Mark mark(@Nullable String name, @Nullable String note,
            @Nullable String service) {
        return marks.create(name, service, note, null);
    }

    public Report mark(Marks.Mark mark) {
        return new Report(Codecs.mark(mark), Text.mark(mark));
    }

    /** Acknowledges a finding, which the CLI must be able to do with no server running. */
    public Acks.Ack ack(@Nullable String findingId, @Nullable String note) {
        return acks.ack(findingId, note);
    }

    public Report ack(Acks.Ack ack) {
        return new Report(Codecs.ack(ack), Text.ack(ack));
    }

    /**
     * What a withdrawn acknowledgement reads as.
     *
     * <p>Static, and the only report here that is: {@code DELETE} answers
     * {@code 204} with no body (api.adoc), so the CLI has nothing to print unless it
     * renders the line itself — and it must render it through this file rather
     * than write one of its own, or the two paths would drift.
     */
    public static Report unack(String findingId) {
        return new Report(Json.obj().put("findingId", findingId), Text.unack(findingId));
    }

    /** Resolves a finding, which the CLI must be able to do with no server running. */
    public Acks.Ack resolve(@Nullable String findingId, @Nullable String note) {
        return acks.resolve(findingId, note);
    }

    public Report resolve(Acks.Ack resolution) {
        return new Report(Codecs.ack(resolution), Text.resolve(resolution));
    }

    /** What a withdrawn resolution reads as; static for the reason {@link #unack} is. */
    public static Report unresolve(String findingId) {
        return new Report(Json.obj().put("findingId", findingId), Text.unresolve(findingId));
    }

    public Report acks(int limit) {
        List<Acks.Ack> list = acks.all(limit);
        return new Report(Json.obj().put("acks", Codecs.acks(list)), Text.acks(list));
    }

    /**
     * The two windows side by side.
     *
     * <p>{@code before} is {@code [before, after)} and {@code after} is
     * {@code [after, until)}: the instant a mark names belongs to the window that
     * starts with it, so exercising, marking and exercising again gives two windows
     * that do not share a request.
     *
     * <p>A window that would be empty or inverted is refused rather than clamped,
     * the way every other window with {@code since} after {@code until} is.
     */
    public Report compare(long before, long after, long until, @Nullable String service,
            boolean full) {
        if (before >= after) {
            throw new Selectors.BadSelector("before resolves to " + before
                    + ", which is not before after " + after);
        }
        if (after >= until) {
            throw new Selectors.BadSelector("after resolves to " + after
                    + ", which is not before until " + until);
        }
        Window first = Window.of(before, after - 1);
        Window second = Window.of(after, until - 1);
        Compare.Comparison comparison = compare.compare(first, second, service);
        return new Report(Codecs.comparison(comparison), Text.compare(comparison, service, full));
    }

    public Report check(Window window, @Nullable String service, @Nullable String endpoint,
            Map<String, Double> rules) {
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
    public @Nullable Report trace(String traceId, boolean full) {
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace == null) {
            return null;
        }
        return new Report(Codecs.trace(trace, tingles), Text.trace(trace, tingles, frames, full));
    }

    /**
     * Two traces aligned: which span went away, and which one got slower
     * (cli.adoc#trace-diff).
     *
     * <p>Both sides are reduced to the lines the single rendering would have
     * printed and aligned by their longest common subsequence, so the diff and the
     * tree can never disagree about what a line is. {@code full} expands the
     * collapsed groups on both sides before the alignment, which is the only thing
     * it changes here.
     *
     * @throws NoSuchTrace when either id is not stored
     */
    public Report traceDiff(String a, String b, boolean full) {
        Queries.TraceDetail one = stored(a);
        Queries.TraceDetail two = stored(b);
        List<Text.DiffLine> lines = Text.align(Text.lines(one, tingles, frames, full),
                Text.lines(two, tingles, frames, full));
        return new Report(Codecs.traceDiff(one, two, lines), Text.traceDiff(one, two, lines));
    }

    private Queries.TraceDetail stored(String traceId) {
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace == null) {
            throw new NoSuchTrace(traceId);
        }
        return trace;
    }

    public Report endpoints(Window window, @Nullable String service) {
        List<Stats.EndpointStats> endpoints = queries.endpoints(window, service, null);
        long requests = queries.totals(window, service).requests();
        return new Report(Json.obj().put("endpoints", Codecs.endpoints(endpoints)),
                Text.endpoints(window, service, endpoints, requests, endpoint()));
    }

    public Report queries(Window window, @Nullable String service, @Nullable String sort, int limit,
            boolean full) {
        List<Stats.QueryStats> list = queries.queries(window, service, sort, limit, null);
        long requests = queries.totals(window, service).requests();
        return new Report(Json.obj().put("queries", Codecs.queries(list)),
                Text.queries(window, service, list, requests, full, endpoint()));
    }

    /**
     * The error groups of the window, each with its occurrences per bucket as the
     * {@code series} the errors page draws as a sparkline (api.adoc#error-group); the text
     * rendering has no use for a sparkline and does not carry it.
     */
    public Report errors(Window window, @Nullable String service, int limit, boolean full) {
        List<Stats.ErrorGroup> list = queries.errors(window, service, limit, null);
        long requests = queries.totals(window, service).requests();
        List<String> ids = new ArrayList<>(list.size());
        list.forEach(group -> ids.add(group.errorId()));
        Map<String, long[]> series = queries.errorSeries(window, ids);
        return new Report(Json.obj().put("errors", Codecs.errorGroups(list, series)),
                Text.errors(window, service, list, requests, full, frames, endpoint()));
    }

    /**
     * One error group, as the list renders it: its row and its frames
     * (cli.adoc#one-finding). Text only: the JSON of {@code /api/errors/{errorId}} is the
     * page's, with its series and traces, and stays where it is.
     *
     * @return null when the group has no occurrence in the window
     */
    public @Nullable String errorText(Window window, @Nullable String service, String errorId,
            boolean full) {
        List<Stats.ErrorGroup> found = queries.errors(window, null, 1, errorId);
        if (found.isEmpty()) {
            return null;
        }
        long requests = queries.totals(window, service).requests();
        return Text.error(window, service, found.get(0), requests, full, frames);
    }

    /**
     * One query group, as the list renders it: its row (cli.adoc#one-finding).
     * Text only, for the reason {@link #errorText} is.
     *
     * @return null when the group has no call in the window
     */
    public @Nullable String queryText(Window window, @Nullable String service, String queryId,
            boolean full) {
        List<Stats.QueryStats> found = queries.queries(window, null, "total", 1, queryId);
        if (found.isEmpty()) {
            return null;
        }
        long requests = queries.totals(window, service).requests();
        return Text.query(window, service, found.get(0), requests, full);
    }

    public Report logs(Queries.LogFilter filter) {
        List<net.benelog.spidersense.store.LogRecord> logs = queries.logs(filter);
        long total = queries.logTotal(filter);
        Json.JsonObject json = Json.obj()
                .put("logs", Codecs.logs(logs))
                .put("total", total);
        // Only an empty answer says the count, and only a window with no request says to send some.
        long requests = logs.isEmpty() ? queries.totals(filter.window(), filter.service()).requests() : 0;
        return new Report(json, Text.logs(filter.window(), filter.service(), logs, total, requests,
                endpoint()));
    }

    /**
     * One read-only statement over the schema of storage.adoc#schema: the question findings
     * cannot answer (cli.adoc#sql).
     *
     * <p>It goes through here like every other answer, and for the same reason:
     * the CLI's direct-file path must give the same rows as the HTTP one, down to
     * the row cap and the truncation notice.
     *
     * @throws ReadOnlyQuery.Refused when the statement is not one this may run, or
     *                               when H2 refuses it
     * @throws Database.ReaderUnavailable when the database has no reader user yet
     */
    public Report sql(@Nullable String statement, int limit, boolean full) {
        ReadOnlyQuery.Result result = readOnly.run(statement, limit);
        return new Report(Codecs.sqlResult(result), Text.sql(result, limit, full));
    }

    // --- export and import -----------------------------------------------------

    /**
     * The window as one JSON document, written to {@code out} as it is read
     * (cli.adoc#export-import).
     *
     * <p>Both callers come through here for the same reason every other answer
     * does: the file the CLI writes and the download the browser gets must be the
     * same bytes.
     */
    public void export(Window window, @Nullable String service, OutputStream out) {
        SessionExport.write(database.sql(), window, service, out);
    }

    /** {@code spider-sense-<from>-<to>.json}: what the download is called. */
    public static String exportFilename(Window window) {
        return SessionExport.filename(window);
    }

    /**
     * An exported document, written back.
     *
     * @throws Importer.WrongSchema when the document is of another schema version
     */
    public Importer.Result importDocument(Json.JsonObject document) {
        return importer().importDocument(document);
    }

    /**
     * The importer, the store's with a store and one of its own without: the CLI
     * writing into the file it opened needs no writer, because an import is one
     * transaction on this thread rather than write-behind (storage.adoc).
     */
    private Importer importer() {
        return store != null ? store.importer() : new Importer(database.sql(), tingles);
    }

    public Report imported(Importer.Result result) {
        Json.JsonObject json = Json.obj()
                .put("spans", result.spans())
                .put("logs", result.logs())
                .put("metricPoints", result.metricPoints())
                .put("tingles", result.tingles())
                .put("marks", result.marks())
                .put("dbTables", result.dbTables())
                .put("skippedTraces", result.skippedTraces())
                .put("window", Json.obj().put("from", result.from()).put("to", result.to()));
        return new Report(json, Text.imported(result));
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
