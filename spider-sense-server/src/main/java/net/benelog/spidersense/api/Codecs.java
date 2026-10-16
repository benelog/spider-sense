package net.benelog.spidersense.api;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

import net.benelog.spidersense.query.Catalog;
import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.JvmView;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.SchemaBlock;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Version;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.AttrJson;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.ExceptionChain;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingle;
import net.benelog.spidersense.store.Tingles;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonValue;
import net.benelog.silkjson.JsonObject;
import net.benelog.silkjson.JsonArray;
import org.jspecify.annotations.Nullable;

/**
 * The records' shapes on the wire, written by hand.
 *
 * <p>api.adoc is the contract and this file implements the shape of every record it
 * names: each field name of those records appears here literally, so a reader can check
 * the two side by side. Each route's envelope, the object that wraps the records it
 * answers with, is written in its handler, so a change to the contract is a change to
 * this file and to the handler of the route it concerns.
 *
 * <p>Two rules run through it. A duration that has no value — a percentile of an
 * empty bucket, the rate of the first point of a series — is written as
 * {@code null}, never as a zero that would draw a line down to the axis. And
 * {@code NaN} never reaches the wire, because it is not JSON.
 */
public final class Codecs {

    private Codecs() {
    }

    // --- primitives ---------------------------------------------------------

    /** A double, or {@code null} when it is not a number (an absent value). */
    static JsonObject put(JsonObject object, String key, double value) {
        if (!Double.isFinite(value)) {
            return object.putNull(key);
        }
        return object.put(key, round(value));
    }

    static JsonObject put(JsonObject object, String key, @Nullable Long value) {
        return value == null ? object.putNull(key) : object.put(key, value.longValue());
    }

    /** A score that is absent rather than zero — an Apdex over no request at all. */
    static JsonObject put(JsonObject object, String key, @Nullable Double value) {
        return value == null ? object.putNull(key) : put(object, key, value.doubleValue());
    }

    /**
     * Three decimals: a millisecond duration is not interesting below a microsecond.
     * Past 1e12 the double holds no third decimal to drop, and {@code value * 1000} would saturate
     * {@link Math#round(double)} at {@code Long.MAX_VALUE}, so a large finite value is written as it is.
     */
    static double round(double value) {
        if (Double.isFinite(value) && Math.abs(value) >= 1e12) {
            return value;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    static JsonArray longs(long[] values) {
        JsonArray array = Json.array();
        for (long value : values) {
            array.add(value);
        }
        return array;
    }

    /** The response-time histogram of a series: one array of counts per bucket. */
    static JsonArray histogram(long[][] buckets) {
        JsonArray array = Json.array();
        for (long[] bucket : buckets) {
            array.add(longs(bucket));
        }
        return array;
    }

    static JsonArray doubles(double[] values) {
        JsonArray array = Json.array();
        for (double value : values) {
            if (!Double.isFinite(value)) {
                array.add((JsonValue) null);
            } else {
                array.add(round(value));
            }
        }
        return array;
    }

    /**
     * A metric's values as they are, {@code null} for what is not a number. A metric is in its
     * own unit, seconds as often as milliseconds, so the three decimals of {@link #round} would
     * read a 0.4 ms mean in seconds as {@code 0} (api.adoc#metrics).
     */
    static JsonArray unrounded(double[] values) {
        JsonArray array = Json.array();
        for (double value : values) {
            if (!Double.isFinite(value)) {
                array.add((JsonValue) null);
            } else {
                array.add(value);
            }
        }
        return array;
    }

    /** The same, with {@code null} wherever the bucket held no requests. */
    static JsonArray doubles(double[] values, long[] counts) {
        JsonArray array = Json.array();
        for (int i = 0; i < values.length; i++) {
            if (i < counts.length && counts[i] == 0) {
                array.add((JsonValue) null);
            } else if (!Double.isFinite(values[i])) {
                array.add((JsonValue) null);
            } else {
                array.add(round(values[i]));
            }
        }
        return array;
    }

    /** An attribute map: string, number, boolean, or an array of those. */
    static JsonObject attributes(Map<String, Object> attributes) {
        JsonObject object = Json.object();
        attributes.forEach((key, value) -> object.put(key, value(value)));
        return object;
    }

    /**
     * An attribute's value: a number as the application wrote it, since it is in no unit of ours
     * to round ({@code 0.0004} is a sample rate as often as a duration), {@code null} for what is
     * not a number, and a key/value list not yet stored as its JSON text, as it reads once it is.
     */
    private static final AttrJson.Rules ATTRIBUTE =
            new AttrJson.Rules(AttrJson.NonFinite.NULL, DoubleUnaryOperator.identity(), true);

    /**
     * A finding's {@code numbers}, whose keys are the kind's own: rounded, since they are durations
     * and shares of ours, {@code null} for what is not a number, and a nested map an object, as a
     * slow endpoint's hot span is.
     *
     * <p>They are data rather than schema — every kind names different ones — so they are written
     * by the type of the value, which is the one place in this file that does not read like the
     * contract, because the contract itself says "kind-specific".
     */
    private static final AttrJson.Rules NUMBER =
            new AttrJson.Rules(AttrJson.NonFinite.NULL, Codecs::round, false);

    private static JsonValue value(@Nullable Object value) {
        return AttrJson.toJson(value, ATTRIBUTE);
    }

    static JsonObject window(Window window) {
        return Json.object()
                .put("from", window.from())
                .put("to", window.to())
                .put("bucketMs", window.bucketMs());
    }

    /** The window's bounds alone, without the bucket width {@link #window} adds. */
    static JsonObject bounds(long from, long to) {
        return Json.object().put("from", from).put("to", to);
    }

    /** Each element of a list, as the JSON its codec writes, in the list's order. */
    static <T> JsonArray each(List<T> values, Function<T, ? extends JsonValue> codec) {
        JsonArray array = Json.array();
        values.forEach(value -> array.add(codec.apply(value)));
        return array;
    }

    static JsonArray strings(List<String> values) {
        return Json.array().addAll(values);
    }

    // --- the contract's objects ---------------------------------------------

    /** {@code GET /api/status} (api.adoc#status). */
    static JsonObject status(StatusSnapshot status) {
        JsonObject otlp = Json.object();
        String endpoint = status.endpoint();
        if (endpoint != null) {
            otlp.put("traces", endpoint + "/v1/traces")
                    .put("metrics", endpoint + "/v1/metrics")
                    .put("logs", endpoint + "/v1/logs");
        }
        // ingest.maxSpansPerSecond is always present, null for no cap, so the UI can tell "no
        // cap" from "old server".
        JsonObject ingest = Json.object();
        Long cap = status.maxSpansPerSecond();
        if (cap == null) {
            ingest.putNull("maxSpansPerSecond");
        } else {
            ingest.put("maxSpansPerSecond", cap.longValue());
        }
        Database.Storage storage = status.storage();
        return Json.object()
                .put("name", Version.NAME)
                .put("version", Version.CURRENT)
                .put("mode", status.mode())
                .put("startedAt", status.startedAt())
                .put("now", status.now())
                .put("endpoint", endpoint)
                .put("otlp", otlp)
                .put("embeddedService", status.embeddedService())
                .put("thresholds", Json.object()
                        .put("slowRequestMs", status.slowRequestMs())
                        .put("slowQueryMs", status.slowQueryMs())
                        .put("responseBucketsMs", longs(status.responseBuckets().bounds())))
                .put("ignore", Json.object()
                        .put("endpoints", strings(status.ignoredEndpoints())))
                .put("codeFrames", Json.object()
                        .put("appPackages", strings(status.appPackages()))
                        .put("frameworkPrefixes", strings(status.frameworkPrefixes())))
                .put("jar", status.jar())
                .put("retention", Json.object()
                        .put("hours", status.retentionHours())
                        .put("spans", status.retentionSpans()))
                .put("ingest", ingest)
                .put("storage", Json.object()
                        .put("url", storage.url())
                        .put("path", storage.path())
                        .put("sizeBytes", storage.sizeBytes())
                        .put("fallback", storage.fallback())
                        .put("fallbackReason", storage.fallbackReason())
                        .put("droppedBatches", status.droppedBatches())
                        .put("droppedSpans", status.droppedSpans())
                        .put("queued", status.queued()))
                .put("counts", Json.object()
                        .put("spans", status.spans())
                        .put("traces", status.traces())
                        .put("logs", status.logs())
                        .put("metricSeries", status.metricSeries())
                        .put("services", status.services()))
                .put("services", strings(status.serviceNames()))
                .put("oldest", Json.object()
                        .put("span", status.oldestSpan())
                        .put("log", status.oldestLog()));
    }

    static JsonObject totals(Stats.Totals totals) {
        return putTotals(Json.object(), totals);
    }

    /**
     * The numbers of api.adoc#service-summary, in their order: what the overview's totals, a
     * service summary and a service node of the map all carry.
     */
    private static JsonObject putTotals(JsonObject object, Stats.Totals totals) {
        object.put("requests", totals.requests())
                .put("errors", totals.errors())
                .put("errorRate", round(totals.errorRate()))
                .put("rps", round(totals.rps()));
        put(object, "p50Ms", totals.p50Ms());
        put(object, "p95Ms", totals.p95Ms());
        put(object, "p99Ms", totals.p99Ms());
        put(object, "maxMs", totals.maxMs());
        put(object, "apdex", totals.apdex());
        return object.put("histogram", longs(totals.histogram()));
    }

    static JsonObject serviceSummary(Stats.ServiceSummary service) {
        return putTotals(Json.object()
                        .put("name", service.name())
                        .put("language", service.language())
                        .put("embedded", service.embedded())
                        .put("firstSeen", service.firstSeen())
                        .put("lastSeen", service.lastSeen()),
                service.totals())
                .put("sparkline", longs(service.sparkline()))
                .put("hasJvm", service.hasJvm());
    }

    static JsonArray serviceSummaries(List<Stats.ServiceSummary> services) {
        return each(services, Codecs::serviceSummary);
    }

    static JsonObject tingle(Tingle tingle) {
        JsonObject object = Json.object()
                .put("kind", tingle.kind())
                .put("at", tingle.at())
                .put("service", tingle.service())
                .put("title", tingle.title())
                .put("detail", tingle.detail())
                .put("traceId", tingle.traceId())
                .put("spanId", tingle.spanId());
        return put(object, "durationMs", tingle.durationMs());
    }

    static JsonArray tingles(List<Tingle> tingles) {
        return each(tingles, Codecs::tingle);
    }

    /** The overview's aligned arrays; percentiles are null in an empty bucket. */
    static JsonObject overviewSeries(Stats.Buckets buckets) {
        return Json.object()
                .put("t", longs(buckets.t()))
                .put("requests", longs(buckets.requests()))
                .put("errors", longs(buckets.errors()))
                .put("p95Ms", doubles(buckets.p95Ms(), buckets.requests()))
                .put("histogram", histogram(buckets.histogram()));
    }

    static JsonObject serviceSeries(Stats.Buckets buckets) {
        return Json.object()
                .put("t", longs(buckets.t()))
                .put("requests", longs(buckets.requests()))
                .put("errors", longs(buckets.errors()))
                .put("p50Ms", doubles(buckets.p50Ms(), buckets.requests()))
                .put("p95Ms", doubles(buckets.p95Ms(), buckets.requests()))
                .put("p99Ms", doubles(buckets.p99Ms(), buckets.requests()))
                .put("histogram", histogram(buckets.histogram()));
    }

    static JsonObject endpoint(Stats.EndpointStats endpoint) {
        JsonObject statusCodes = Json.object();
        endpoint.statusCodes().forEach((code, count) -> statusCodes.put(code, count.longValue()));
        JsonObject object = Json.object()
                .put("endpointId", endpoint.endpointId())
                .put("service", endpoint.service())
                .put("method", endpoint.method())
                .put("route", endpoint.route())
                .put("name", endpoint.name())
                .put("kind", endpoint.kind())
                .put("calls", endpoint.calls())
                .put("errors", endpoint.errors())
                .put("errorRate", round(endpoint.errorRate()))
                .put("rps", round(endpoint.rps()));
        put(object, "avgMs", endpoint.avgMs());
        put(object, "p50Ms", endpoint.p50Ms());
        put(object, "p95Ms", endpoint.p95Ms());
        put(object, "p99Ms", endpoint.p99Ms());
        put(object, "maxMs", endpoint.maxMs());
        put(object, "totalMs", endpoint.totalMs());
        put(object, "apdex", endpoint.apdex());
        return object
                .put("histogram", longs(endpoint.histogram()))
                .put("statusCodes", statusCodes);
    }

    static JsonArray endpoints(List<Stats.EndpointStats> endpoints) {
        return each(endpoints, Codecs::endpoint);
    }

    static JsonObject query(Stats.QueryStats query) {
        JsonArray callers = Json.array();
        query.callers().forEach(caller -> callers.add(Json.object()
                .put("endpoint", caller.endpoint())
                .put("endpointId", caller.endpointId())
                .put("service", caller.service())
                .put("calls", caller.calls())));
        JsonObject object = Json.object()
                .put("queryId", query.queryId())
                .put("service", query.service())
                .put("system", query.system())
                .put("namespace", query.namespace())
                .put("operation", query.operation())
                .put("table", query.table())
                .put("statement", query.statement())
                .put("statementCut", SpanRecord.statementMayBeCut(query.statement()))
                .put("calls", query.calls())
                .put("errors", query.errors());
        put(object, "avgMs", query.avgMs());
        put(object, "p50Ms", query.p50Ms());
        put(object, "p95Ms", query.p95Ms());
        put(object, "maxMs", query.maxMs());
        put(object, "totalMs", query.totalMs());
        return object
                .put("slowCalls", query.slowCalls())
                .put("callers", callers)
                .put("lastSeen", query.lastSeen())
                .put("schema", schema(query.schema()));
    }

    /**
     * The schema block of findings.adoc#schema, or JSON null when the statement has none.
     *
     * <p>Null rather than an empty object, because "no index serves nothing" and
     * "nobody could tell" are different answers and the UI shows them differently.
     */
    static @Nullable JsonObject schema(@Nullable SchemaBlock block) {
        if (block == null) {
            return null;
        }
        JsonArray tables = Json.array();
        for (Catalog.Table table : block.tables()) {
            JsonArray indexes = Json.array();
            for (Catalog.Index index : table.indexes()) {
                indexes.add(Json.object()
                        .put("name", index.name())
                        .put("unique", index.unique())
                        .put("columns", strings(index.columns())));
            }
            tables.add(Json.object()
                    .put("table", table.name())
                    .put("schema", table.schema())
                    .put("indexes", indexes));
        }
        return Json.object()
                .put("tables", tables)
                .put("predicates", strings(block.predicates()))
                .put("unindexed", strings(block.unindexed()));
    }

    static JsonArray queries(List<Stats.QueryStats> queries) {
        return each(queries, Codecs::query);
    }

    /** The exception chain of an error's sample, innermost first (api.adoc#cause). */
    static JsonArray chain(ExceptionChain chain) {
        JsonArray array = Json.array();
        chain.causes().forEach(cause -> array.add(Json.object()
                .put("type", cause.type())
                .put("message", cause.message())
                .put("frames", strings(cause.frames()))
                .put("more", cause.more())));
        return array;
    }

    static JsonObject errorGroup(Stats.ErrorGroup group) {
        JsonArray endpoints = Json.array();
        group.endpoints().forEach(endpoint -> endpoints.add(Json.object()
                .put("name", endpoint.name())
                .put("endpointId", endpoint.endpointId())
                .put("count", endpoint.count())));
        JsonObject sample = null;
        if (group.sample() != null) {
            sample = Json.object()
                    .put("traceId", group.sample().traceId())
                    .put("spanId", group.sample().spanId())
                    .put("at", group.sample().at())
                    .put("message", group.sample().message())
                    .put("stacktrace", group.sample().stacktrace());
        }
        return Json.object()
                .put("errorId", group.errorId())
                .put("service", group.service())
                .put("type", group.type())
                .put("message", group.message())
                .put("count", group.count())
                .put("firstSeen", group.firstSeen())
                .put("lastSeen", group.lastSeen())
                .put("endpoints", endpoints)
                .put("sample", sample);
    }

    static JsonArray errorGroups(List<Stats.ErrorGroup> groups) {
        return each(groups, Codecs::errorGroup);
    }

    /** The same, each group with its {@code series} for the errors page's sparkline (api.adoc#error-group). */
    static JsonArray errorGroups(List<Stats.ErrorGroup> groups, Map<String, long[]> series) {
        JsonArray array = Json.array();
        groups.forEach(group -> {
            long[] counts = series.get(group.errorId());
            array.add(errorGroup(group).put("series", longs(counts == null ? new long[0] : counts)));
        });
        return array;
    }

    static JsonObject traceSummary(Stats.TraceSummary trace) {
        JsonObject object = Json.object()
                .put("traceId", trace.traceId())
                .put("start", trace.start());
        put(object, "durationMs", trace.durationMs());
        object.put("rootName", trace.rootName())
                .put("rootService", trace.rootService())
                .put("rootKind", trace.rootKind())
                .put("services", strings(trace.services()))
                .put("spanCount", trace.spanCount())
                .put("errorCount", trace.errorCount())
                .put("dbCount", trace.dbCount());
        put(object, "httpStatus", trace.httpStatus());
        return object.put("slow", trace.slow()).put("error", trace.error());
    }

    static JsonArray traceSummaries(List<Stats.TraceSummary> traces) {
        return each(traces, Codecs::traceSummary);
    }

    /** The slowest traces of a group, each with {@code matchMs}, the group's own span they are ordered by. */
    static JsonArray matchedTraces(List<Queries.MatchedTrace> traces) {
        return each(traces, matched -> put(traceSummary(matched.trace()), "matchMs", matched.matchMs()));
    }

    static JsonObject span(SpanRecord span, Tingles tingles) {
        JsonArray events = Json.array();
        for (SpanRecord.SpanEvent event : span.events()) {
            events.add(Json.object()
                    .put("name", event.name())
                    .put("time", event.timeNanos() / 1_000_000L)
                    .put("attributes", attributes(event.attributes())));
        }
        JsonObject object = Json.object()
                .put("spanId", span.spanId())
                .put("parentSpanId", span.parentSpanId())
                .put("service", span.service())
                .put("name", span.name())
                .put("kind", span.kind())
                .put("start", span.startMillis())
                .put("startNs", span.startNanos());
        put(object, "durationMs", span.durationMillis());
        return object
                .put("durationNs", span.durationNanos())
                .put("status", span.status())
                .put("statusMessage", span.statusMessage())
                .put("attributes", attributes(span.attributes()))
                .put("events", events)
                .put("scope", span.scope())
                .put("category", span.category())
                .put("summary", span.summary())
                .put("slow", tingles.isSlow(span))
                .put("error", span.isError());
    }

    static JsonObject trace(Queries.TraceDetail trace, Tingles tingles) {
        JsonArray spans = Json.array();
        trace.spans().forEach(span -> spans.add(span(span, tingles)));
        JsonObject object = Json.object()
                .put("traceId", trace.traceId())
                .put("start", trace.start())
                .put("end", trace.end());
        put(object, "durationMs", trace.durationMs());
        return object
                .put("services", strings(trace.services()))
                .put("spans", spans)
                .put("logs", logs(trace.logs()));
    }

    /**
     * Two traces aligned, as cli.adoc#trace-diff writes them.
     *
     * <p>A line names only what the alignment worked on — the depth, the category
     * and the summary — because those are the fields the key was built from, and
     * a reader that wants the whole span already has the trace id to ask for it.
     * {@code count} is present only where a line stands for a collapsed group on
     * either side, and {@code null} on the side that has no such line.
     */
    static JsonObject traceDiff(Queries.TraceDetail a, Queries.TraceDetail b,
            List<Text.DiffLine> lines) {
        JsonArray array = Json.array();
        for (Text.DiffLine line : lines) {
            Text.TraceLine shown = line.either();
            JsonObject object = Json.object()
                    .put("op", String.valueOf(line.op()))
                    .put("depth", (long) shown.depth())
                    .put("summary", shown.span().summary())
                    .put("category", shown.span().category());
            put(object, "aMs", line.a() == null ? null : (Double) line.a().durationMs());
            put(object, "bMs", line.b() == null ? null : (Double) line.b().durationMs());
            if (line.counted()) {
                JsonObject count = Json.object();
                put(count, "a", line.a() == null ? null : (Long) (long) line.a().count());
                put(count, "b", line.b() == null ? null : (Long) (long) line.b().count());
                object.put("count", count);
            } else {
                object.putNull("count");
            }
            array.add(object);
        }
        JsonObject durations = Json.object();
        put(durations, "a", (Double) a.durationMs());
        put(durations, "b", (Double) b.durationMs());
        return Json.object()
                .put("a", a.traceId())
                .put("b", b.traceId())
                .put("durationMs", durations)
                .put("spans", Json.object()
                        .put("a", (long) a.spans().size())
                        .put("b", (long) b.spans().size()))
                .put("lines", array);
    }

    static JsonObject log(LogRecord log) {
        return Json.object()
                .put("id", log.id())
                .put("at", log.at())
                .put("service", log.service())
                .put("severity", log.severity())
                .put("severityNumber", log.severityNumber())
                .put("body", log.body())
                .put("logger", log.logger())
                .put("traceId", log.traceId())
                .put("spanId", log.spanId())
                .put("attributes", attributes(log.attributes()));
    }

    static JsonArray logs(List<LogRecord> logs) {
        return each(logs, Codecs::log);
    }

    static JsonArray dependencies(List<Stats.Dependency> dependencies) {
        JsonArray array = Json.array();
        for (Stats.Dependency dependency : dependencies) {
            JsonObject object = Json.object()
                    .put("kind", dependency.kind())
                    .put("target", dependency.target())
                    .put("calls", dependency.calls())
                    .put("errors", dependency.errors());
            put(object, "avgMs", dependency.avgMs());
            put(object, "p95Ms", dependency.p95Ms());
            array.add(object);
        }
        return array;
    }

    /**
     * The service map. A service node carries the service summary's numbers; every
     * other node carries the numbers of the calls made to it, so the two halves of
     * {@link Stats.Node} are never written together.
     */
    static JsonObject map(Window window, Stats.ServiceMap map) {
        JsonArray nodes = Json.array();
        for (Stats.Node node : map.nodes()) {
            JsonObject object = Json.object()
                    .put("id", node.id())
                    .put("kind", node.kind())
                    .put("name", node.name());
            Stats.Totals totals = node.totals();
            if (totals != null) {
                putTotals(object, totals).put("hasJvm", node.hasJvm());
            } else if (!"user".equals(node.kind())) {
                object.put("calls", node.calls()).put("errors", node.errors());
                put(object, "avgMs", node.avgMs());
                put(object, "p95Ms", node.p95Ms());
            }
            nodes.add(object);
        }
        JsonArray edges = Json.array();
        for (Stats.Edge edge : map.edges()) {
            JsonObject object = Json.object()
                    .put("from", edge.from())
                    .put("to", edge.to())
                    .put("calls", edge.calls())
                    .put("errors", edge.errors());
            put(object, "avgMs", edge.avgMs());
            put(object, "p95Ms", edge.p95Ms());
            edges.add(object);
        }
        return Json.object()
                .put("window", window(window))
                .put("nodes", nodes)
                .put("edges", edges);
    }

    /** Arrays, not objects: five thousand points have to stay small on the wire. */
    static JsonArray scatter(List<Stats.ScatterPoint> points) {
        JsonArray array = Json.array();
        for (Stats.ScatterPoint point : points) {
            array.add(Json.array()
                    .add(point.start())
                    .add(round(point.durationMs()))
                    .add(point.service())
                    .add(point.endpoint())
                    .add(point.traceId())
                    .add(point.flags()));
        }
        return array;
    }

    static JsonArray metricCatalog(List<MetricQueries.MetricMeta> catalog) {
        JsonArray array = Json.array();
        for (MetricQueries.MetricMeta metric : catalog) {
            array.add(Json.object()
                    .put("name", metric.name())
                    .put("type", metric.type())
                    .put("unit", metric.unit())
                    .put("description", metric.description())
                    .put("services", strings(metric.services()))
                    .put("series", metric.seriesCount()));
        }
        return array;
    }

    /**
     * One metric series. A histogram carries its count, estimated p95 and maximum
     * beside the mean, because a mean alone hides exactly what a histogram is for.
     */
    static JsonObject series(MetricQueries.SeriesData data, boolean rate) {
        List<MetricPoint> points = data.points();
        long[] t = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            t[i] = points.get(i).at();
            values[i] = points.get(i).value();
        }
        if (rate && data.monotonic()) {
            values = MetricQueries.rate(points, "DELTA".equals(data.temporality()));
        }
        JsonObject object = Json.object()
                .put("service", data.service())
                .put("attributes", attributes(data.attributes()))
                .put("t", longs(t))
                .put("v", unrounded(values));
        if ("histogram".equals(data.type())) {
            JsonArray counts = Json.array();
            double[] p95 = new double[points.size()];
            double[] max = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                counts.add(points.get(i).count());
                p95[i] = points.get(i).percentile(0.95);
                max[i] = points.get(i).max();
            }
            object.put("count", counts).put("p95", unrounded(p95)).put("max", unrounded(max));
        }
        return object;
    }

    static JsonObject jvm(JvmView jvm) {
        JsonArray pools = Json.array();
        for (JvmView.Pool pool : jvm.pools()) {
            pools.add(Json.object()
                    .put("name", pool.name())
                    .put("type", pool.type())
                    .put("t", longs(pool.t()))
                    .put("used", doubles(pool.used())));
        }
        JsonArray gc = Json.array();
        for (JvmView.Gc collector : jvm.gc()) {
            gc.add(Json.object()
                    .put("name", collector.name())
                    .put("action", collector.action())
                    .put("t", longs(collector.t()))
                    .put("count", longs(collector.count()))
                    .put("durationMs", doubles(collector.durationMs())));
        }
        JsonArray connectionPools = Json.array();
        for (JvmView.ConnectionPool pool : jvm.connectionPools()) {
            connectionPools.add(Json.object()
                    .put("name", pool.name())
                    .put("t", longs(pool.t()))
                    .put("used", doubles(pool.used()))
                    .put("idle", doubles(pool.idle()))
                    .put("max", doubles(pool.max()))
                    .put("pending", doubles(pool.pending())));
        }
        JsonObject runtime = Json.object().put("jvm", jvm.runtime().jvm());
        put(runtime, "pid", jvm.runtime().pid());
        runtime.put("host", jvm.runtime().host());
        put(runtime, "cpuCount", jvm.runtime().cpuCount());

        return Json.object()
                .put("service", jvm.service())
                .put("runtime", runtime)
                .put("heap", Json.object()
                        .put("t", longs(jvm.heap().t()))
                        .put("used", doubles(jvm.heap().used()))
                        .put("committed", doubles(jvm.heap().committed()))
                        .put("limit", doubles(jvm.heap().limit())))
                .put("nonHeap", Json.object()
                        .put("t", longs(jvm.nonHeap().t()))
                        .put("used", doubles(jvm.nonHeap().used()))
                        .put("committed", doubles(jvm.nonHeap().committed())))
                .put("pools", pools)
                .put("gc", gc)
                .put("threads", Json.object()
                        .put("t", longs(jvm.threads().t()))
                        .put("count", doubles(jvm.threads().count()))
                        .put("daemon", doubles(jvm.threads().daemon())))
                .put("cpu", Json.object()
                        .put("t", longs(jvm.cpu().t()))
                        .put("utilization", doubles(jvm.cpu().utilization()))
                        .put("systemLoad1m", doubles(jvm.cpu().systemLoad1m())))
                .put("classes", Json.object()
                        .put("t", longs(jvm.classes().t()))
                        .put("loaded", doubles(jvm.classes().loaded())))
                .put("connectionPools", connectionPools);
    }

    // --- the agent interface --------------------------------------------------

    static JsonObject mark(Marks.Mark mark) {
        return Json.object()
                .put("id", mark.id())
                .put("at", mark.at())
                .put("name", mark.name())
                .put("service", mark.service())
                .put("note", mark.note());
    }

    static JsonArray marks(List<Marks.Mark> marks) {
        return each(marks, Codecs::mark);
    }

    static JsonObject finding(Findings.Finding finding) {
        JsonObject numbers = Json.object();
        finding.numbers().forEach((key, value) -> numbers.put(key, AttrJson.toJson(value, NUMBER)));
        return Json.object()
                .put("id", finding.id())
                .put("kind", finding.kind())
                .put("severity", finding.severity())
                .put("state", finding.state())
                .put("service", finding.service())
                .put("title", finding.title())
                .put("why", finding.why())
                .put("subject", Json.object()
                        .put("endpointId", finding.subject().endpointId())
                        .put("queryId", finding.subject().queryId())
                        .put("errorId", finding.subject().errorId())
                        .put("pool", finding.subject().pool())
                        .put("job", finding.subject().job())
                        .put("target", finding.subject().target())
                        .put("logger", finding.subject().logger())
                        .put("jvm", finding.subject().jvm()))
                .put("numbers", numbers)
                .put("statement", finding.statement())
                .put("statementCut", SpanRecord.statementMayBeCut(finding.statement()))
                .put("code", strings(finding.code()))
                .put("traces", strings(finding.traces()))
                .put("schema", schema(finding.schema()))
                .put("ack", finding.ack() == null ? null
                        : Json.object().put("at", finding.ack().at()).put("note", finding.ack().note()))
                .put("resolution", finding.resolution() == null ? null
                        : Json.object().put("at", finding.resolution().at())
                                .put("note", finding.resolution().note()));
    }

    static JsonArray findings(List<Findings.Finding> findings) {
        return each(findings, Codecs::finding);
    }

    static JsonObject ack(Acks.Ack ack) {
        return Json.object()
                .put("findingId", ack.findingId())
                .put("at", ack.at())
                .put("note", ack.note());
    }

    static JsonArray acks(List<Acks.Ack> acks) {
        return each(acks, Codecs::ack);
    }


    static JsonObject comparison(Compare.Comparison comparison) {
        JsonArray endpoints = Json.array();
        for (Compare.EndpointDiff diff : comparison.endpoints()) {
            endpoints.add(Json.object()
                    .put("endpointId", diff.endpointId())
                    .put("service", diff.service())
                    .put("name", diff.name())
                    .put("before", endpointSide(diff.before()))
                    .put("after", endpointSide(diff.after()))
                    .put("verdict", diff.verdict()));
        }
        JsonArray queries = Json.array();
        for (Compare.QueryDiff diff : comparison.queries()) {
            queries.add(Json.object()
                    .put("queryId", diff.queryId())
                    .put("service", diff.service())
                    .put("statement", diff.statement())
                    .put("statementCut", SpanRecord.statementMayBeCut(diff.statement()))
                    .put("before", querySide(diff.before()))
                    .put("after", querySide(diff.after()))
                    .put("verdict", diff.verdict()));
        }
        JsonArray errors = Json.array();
        for (Compare.ErrorDiff diff : comparison.errors()) {
            errors.add(Json.object()
                    .put("errorId", diff.errorId())
                    .put("service", diff.service())
                    .put("type", diff.type())
                    .put("message", diff.message())
                    .put("before", diff.before())
                    .put("after", diff.after())
                    .put("verdict", diff.verdict()));
        }
        return Json.object()
                .put("before", bounds(comparison.before().from(), comparison.before().to()))
                .put("after", bounds(comparison.after().from(), comparison.after().to()))
                .put("totals", Json.object()
                        .put("before", totals(comparison.beforeTotals()))
                        .put("after", totals(comparison.afterTotals())))
                .put("endpoints", endpoints)
                .put("queries", queries)
                .put("errors", errors);
    }

    private static @Nullable JsonValue endpointSide(Compare.@Nullable Side side) {
        if (side == null) {
            return null;
        }
        JsonObject object = Json.object()
                .put("calls", side.calls())
                .put("errors", side.errors());
        put(object, "p50Ms", side.p50Ms());
        put(object, "p95Ms", side.p95Ms());
        put(object, "maxMs", side.maxMs());
        put(object, "dbCallsPerRequest", side.dbCallsPerRequest());
        put(object, "dbMsPerRequest", side.dbMsPerRequest());
        return object;
    }

    private static @Nullable JsonValue querySide(Compare.@Nullable QuerySide side) {
        if (side == null) {
            return null;
        }
        JsonObject object = Json.object().put("calls", side.calls());
        put(object, "callsPerRequest", side.callsPerRequest());
        put(object, "p95Ms", side.p95Ms());
        put(object, "totalMs", side.totalMs());
        return object;
    }

    static JsonObject checkResult(Check.CheckResult result) {
        JsonArray checks = Json.array();
        for (Check.RuleCheck check : result.checks()) {
            // Unrounded, unlike a duration: a rule that fails by less than a thousandth, an error
            // rate of 0.0004 over a limit of 0, must not read as its limit beside pass: false.
            JsonObject object = Json.object().put("rule", check.rule()).put("limit", check.limit());
            Double actual = check.actual();
            if (actual == null || !Double.isFinite(actual)) {
                object.putNull("actual");
            } else {
                object.put("actual", actual.doubleValue());
            }
            checks.add(object.put("pass", check.pass()).put("detail", check.detail()));
        }
        JsonObject object = Json.object();
        Boolean pass = result.verdict().pass();
        if (pass == null) {
            object.putNull("pass");
        } else {
            object.put("pass", pass.booleanValue());
        }
        return object
                .put("requests", result.requests())
                .put("reason", result.reason())
                .put("checks", checks);
    }

    /**
     * The answer of {@code POST /api/sql}: the columns, the rows, and how the rows
     * ended.
     *
     * <p>{@code truncated} is not a courtesy. An agent that reads 200 rows of an
     * answer that had 40,000 and says "there are 200" would be wrong, so the cap
     * is always visible beside the count.
     */
    static JsonObject sqlResult(ReadOnlyQuery.Result result) {
        JsonArray rows = Json.array();
        for (List<Object> row : result.rows()) {
            JsonArray cells = Json.array();
            for (Object cell : row) {
                cell(cells, cell);
            }
            rows.add(cells);
        }
        return Json.object()
                .put("columns", Json.array().addAll(result.columns()))
                .put("rows", rows)
                .put("rowCount", result.rows().size())
                .put("truncated", result.truncated())
                .put("elapsedMs", result.elapsedMs());
    }

    /** A cell stays the type the store holds it as, unrounded; anything else is its text. */
    private static void cell(JsonArray cells, @Nullable Object value) {
        cells.add(AttrJson.toJson(value, AttrJson.Rules.ANSWER));
    }
}
