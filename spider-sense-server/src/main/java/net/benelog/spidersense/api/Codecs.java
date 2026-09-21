package net.benelog.spidersense.api;

import java.util.List;
import java.util.Map;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.Compare;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.JvmView;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.SchemaBlock;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Acks;
import net.benelog.spidersense.store.LogRecord;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.spidersense.store.ReadOnlyQuery;
import net.benelog.spidersense.store.SpanRecord;
import net.benelog.spidersense.store.Tingle;
import net.benelog.spidersense.store.Tingles;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * The wire format, written by hand, in one place.
 *
 * <p>api.md is the contract and this file is its implementation: every field name
 * the UI reads appears here literally, so a change to the contract is a change to
 * one file and a reader can check the two side by side.
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
    static Json.JsonObject put(Json.JsonObject object, String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return object.putNull(key);
        }
        return object.put(key, round(value));
    }

    static Json.JsonObject put(Json.JsonObject object, String key, @Nullable Long value) {
        return value == null ? object.putNull(key) : object.put(key, value.longValue());
    }

    /** A score that is absent rather than zero — an Apdex over no request at all. */
    static Json.JsonObject put(Json.JsonObject object, String key, @Nullable Double value) {
        return value == null ? object.putNull(key) : put(object, key, value.doubleValue());
    }

    /** Three decimals: a millisecond duration is not interesting below a microsecond. */
    static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    static Json.JsonArray longs(long[] values) {
        Json.JsonArray array = Json.arr();
        for (long value : values) {
            array.add(value);
        }
        return array;
    }

    /** The response-time histogram of a series: one array of counts per bucket. */
    static Json.JsonArray histogram(long[][] buckets) {
        Json.JsonArray array = Json.arr();
        for (long[] bucket : buckets) {
            array.add(longs(bucket));
        }
        return array;
    }

    static Json.JsonArray doubles(double[] values) {
        Json.JsonArray array = Json.arr();
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                array.add((Json.JsonValue) null);
            } else {
                array.add(round(value));
            }
        }
        return array;
    }

    /** The same, with {@code null} wherever the bucket held no requests. */
    static Json.JsonArray doubles(double[] values, long[] counts) {
        Json.JsonArray array = Json.arr();
        for (int i = 0; i < values.length; i++) {
            if (i < counts.length && counts[i] == 0) {
                array.add((Json.JsonValue) null);
            } else if (Double.isNaN(values[i]) || Double.isInfinite(values[i])) {
                array.add((Json.JsonValue) null);
            } else {
                array.add(round(values[i]));
            }
        }
        return array;
    }

    /** An attribute map: string, number, boolean, or an array of those. */
    static Json.JsonObject attributes(Map<String, Object> attributes) {
        Json.JsonObject object = Json.obj();
        attributes.forEach((key, value) -> object.put(key, value(value)));
        return object;
    }

    private static Json.JsonValue value(@Nullable Object value) {
        Json.JsonObject holder = Json.obj();
        switch (value) {
            case null -> holder.putNull("v");
            case String text -> holder.put("v", text);
            case Long number -> holder.put("v", number.longValue());
            case Integer number -> holder.put("v", number.longValue());
            case Double number -> put(holder, "v", number.doubleValue());
            case Boolean flag -> holder.put("v", flag.booleanValue());
            case List<?> list -> {
                Json.JsonArray array = Json.arr();
                for (Object element : list) {
                    array.add(value(element));
                }
                holder.put("v", array);
            }
            default -> holder.put("v", String.valueOf(value));
        }
        return holder.get("v");
    }

    static Json.JsonObject window(Window window) {
        return Json.obj()
                .put("from", window.from())
                .put("to", window.to())
                .put("bucketMs", window.bucketMs());
    }

    static Json.JsonArray strings(List<String> values) {
        return Json.arr().addAll(values);
    }

    // --- the contract's objects ---------------------------------------------

    static Json.JsonObject totals(Stats.Totals totals) {
        Json.JsonObject object = Json.obj()
                .put("requests", totals.requests())
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

    static Json.JsonObject serviceSummary(Stats.ServiceSummary service) {
        Json.JsonObject object = Json.obj()
                .put("name", service.name())
                .put("language", service.language())
                .put("embedded", service.embedded())
                .put("firstSeen", service.firstSeen())
                .put("lastSeen", service.lastSeen())
                .put("requests", service.totals().requests())
                .put("errors", service.totals().errors())
                .put("errorRate", round(service.totals().errorRate()))
                .put("rps", round(service.totals().rps()));
        put(object, "p50Ms", service.totals().p50Ms());
        put(object, "p95Ms", service.totals().p95Ms());
        put(object, "p99Ms", service.totals().p99Ms());
        put(object, "maxMs", service.totals().maxMs());
        put(object, "apdex", service.totals().apdex());
        return object
                .put("histogram", longs(service.totals().histogram()))
                .put("sparkline", longs(service.sparkline()))
                .put("hasJvm", service.hasJvm());
    }

    static Json.JsonArray serviceSummaries(List<Stats.ServiceSummary> services) {
        Json.JsonArray array = Json.arr();
        services.forEach(service -> array.add(serviceSummary(service)));
        return array;
    }

    static Json.JsonObject tingle(Tingle tingle) {
        Json.JsonObject object = Json.obj()
                .put("kind", tingle.kind())
                .put("at", tingle.at())
                .put("service", tingle.service())
                .put("title", tingle.title())
                .put("detail", tingle.detail())
                .put("traceId", tingle.traceId())
                .put("spanId", tingle.spanId());
        return put(object, "durationMs", tingle.durationMs());
    }

    static Json.JsonArray tingles(List<Tingle> tingles) {
        Json.JsonArray array = Json.arr();
        tingles.forEach(tingle -> array.add(tingle(tingle)));
        return array;
    }

    /** The overview's aligned arrays; percentiles are null in an empty bucket. */
    static Json.JsonObject overviewSeries(Stats.Buckets buckets) {
        return Json.obj()
                .put("t", longs(buckets.t()))
                .put("requests", longs(buckets.requests()))
                .put("errors", longs(buckets.errors()))
                .put("p95Ms", doubles(buckets.p95Ms(), buckets.requests()))
                .put("histogram", histogram(buckets.histogram()));
    }

    static Json.JsonObject serviceSeries(Stats.Buckets buckets) {
        return Json.obj()
                .put("t", longs(buckets.t()))
                .put("requests", longs(buckets.requests()))
                .put("errors", longs(buckets.errors()))
                .put("p50Ms", doubles(buckets.p50Ms(), buckets.requests()))
                .put("p95Ms", doubles(buckets.p95Ms(), buckets.requests()))
                .put("p99Ms", doubles(buckets.p99Ms(), buckets.requests()))
                .put("histogram", histogram(buckets.histogram()));
    }

    static Json.JsonObject endpoint(Stats.EndpointStats endpoint) {
        Json.JsonObject statusCodes = Json.obj();
        endpoint.statusCodes().forEach((code, count) -> statusCodes.put(code, count.longValue()));
        Json.JsonObject object = Json.obj()
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

    static Json.JsonArray endpoints(List<Stats.EndpointStats> endpoints) {
        Json.JsonArray array = Json.arr();
        endpoints.forEach(endpoint -> array.add(endpoint(endpoint)));
        return array;
    }

    static Json.JsonObject query(Stats.QueryStats query) {
        Json.JsonArray callers = Json.arr();
        query.callers().forEach(caller -> callers.add(Json.obj()
                .put("endpoint", caller.endpoint())
                .put("service", caller.service())
                .put("calls", caller.calls())));
        Json.JsonObject object = Json.obj()
                .put("queryId", query.queryId())
                .put("service", query.service())
                .put("system", query.system())
                .put("namespace", query.namespace())
                .put("operation", query.operation())
                .put("table", query.table())
                .put("statement", query.statement())
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
     * The schema block of agent.md, or JSON null when the statement has none.
     *
     * <p>Null rather than an empty object, because "no index serves nothing" and
     * "nobody could tell" are different answers and the UI shows them differently.
     */
    static Json.@Nullable JsonObject schema(@Nullable SchemaBlock block) {
        if (block == null) {
            return null;
        }
        Json.JsonArray tables = Json.arr();
        for (SchemaBlock.Table table : block.tables()) {
            Json.JsonArray indexes = Json.arr();
            for (SchemaBlock.Index index : table.indexes()) {
                indexes.add(Json.obj()
                        .put("name", index.name())
                        .put("unique", index.unique())
                        .put("columns", strings(index.columns())));
            }
            tables.add(Json.obj()
                    .put("table", table.table())
                    .put("schema", table.schema())
                    .put("indexes", indexes));
        }
        return Json.obj()
                .put("tables", tables)
                .put("predicates", strings(block.predicates()))
                .put("unindexed", strings(block.unindexed()));
    }

    static Json.JsonArray queries(List<Stats.QueryStats> queries) {
        Json.JsonArray array = Json.arr();
        queries.forEach(query -> array.add(query(query)));
        return array;
    }

    static Json.JsonObject errorGroup(Stats.ErrorGroup group) {
        Json.JsonArray endpoints = Json.arr();
        group.endpoints().forEach(endpoint -> endpoints.add(Json.obj()
                .put("name", endpoint.name())
                .put("count", endpoint.count())));
        Json.JsonObject sample = null;
        if (group.sample() != null) {
            sample = Json.obj()
                    .put("traceId", group.sample().traceId())
                    .put("spanId", group.sample().spanId())
                    .put("at", group.sample().at())
                    .put("message", group.sample().message())
                    .put("stacktrace", group.sample().stacktrace());
        }
        return Json.obj()
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

    static Json.JsonArray errorGroups(List<Stats.ErrorGroup> groups) {
        Json.JsonArray array = Json.arr();
        groups.forEach(group -> array.add(errorGroup(group)));
        return array;
    }

    static Json.JsonObject traceSummary(Stats.TraceSummary trace) {
        Json.JsonObject object = Json.obj()
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

    static Json.JsonArray traceSummaries(List<Stats.TraceSummary> traces) {
        Json.JsonArray array = Json.arr();
        traces.forEach(trace -> array.add(traceSummary(trace)));
        return array;
    }

    static Json.JsonObject span(SpanRecord span, Tingles tingles) {
        Json.JsonArray events = Json.arr();
        for (SpanRecord.SpanEvent event : span.events()) {
            events.add(Json.obj()
                    .put("name", event.name())
                    .put("time", event.timeNanos() / 1_000_000L)
                    .put("attributes", attributes(event.attributes())));
        }
        Json.JsonObject object = Json.obj()
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

    static Json.JsonObject trace(Queries.TraceDetail trace, Tingles tingles) {
        Json.JsonArray spans = Json.arr();
        trace.spans().forEach(span -> spans.add(span(span, tingles)));
        Json.JsonObject object = Json.obj()
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
     * Two traces aligned, as agent.md's Trace diff writes them.
     *
     * <p>A line names only what the alignment worked on — the depth, the category
     * and the summary — because those are the fields the key was built from, and
     * a reader that wants the whole span already has the trace id to ask for it.
     * {@code count} is present only where a line stands for a collapsed group on
     * either side, and {@code null} on the side that has no such line.
     */
    static Json.JsonObject traceDiff(Queries.TraceDetail a, Queries.TraceDetail b,
            List<Text.DiffLine> lines) {
        Json.JsonArray array = Json.arr();
        for (Text.DiffLine line : lines) {
            Text.TraceLine shown = line.either();
            Json.JsonObject object = Json.obj()
                    .put("op", String.valueOf(line.op()))
                    .put("depth", (long) shown.depth())
                    .put("summary", shown.span().summary())
                    .put("category", shown.span().category());
            put(object, "aMs", line.a() == null ? null : (Double) line.a().durationMs());
            put(object, "bMs", line.b() == null ? null : (Double) line.b().durationMs());
            if (line.counted()) {
                Json.JsonObject count = Json.obj();
                put(count, "a", line.a() == null ? null : (Long) (long) line.a().count());
                put(count, "b", line.b() == null ? null : (Long) (long) line.b().count());
                object.put("count", count);
            } else {
                object.putNull("count");
            }
            array.add(object);
        }
        Json.JsonObject durations = Json.obj();
        put(durations, "a", (Double) a.durationMs());
        put(durations, "b", (Double) b.durationMs());
        return Json.obj()
                .put("a", a.traceId())
                .put("b", b.traceId())
                .put("durationMs", durations)
                .put("spans", Json.obj()
                        .put("a", (long) a.spans().size())
                        .put("b", (long) b.spans().size()))
                .put("lines", array);
    }

    static Json.JsonObject log(LogRecord log) {
        return Json.obj()
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

    static Json.JsonArray logs(List<LogRecord> logs) {
        Json.JsonArray array = Json.arr();
        logs.forEach(log -> array.add(log(log)));
        return array;
    }

    static Json.JsonArray dependencies(List<Stats.Dependency> dependencies) {
        Json.JsonArray array = Json.arr();
        for (Stats.Dependency dependency : dependencies) {
            Json.JsonObject object = Json.obj()
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
    static Json.JsonObject map(Window window, Stats.ServiceMap map) {
        Json.JsonArray nodes = Json.arr();
        for (Stats.Node node : map.nodes()) {
            Json.JsonObject object = Json.obj()
                    .put("id", node.id())
                    .put("kind", node.kind())
                    .put("name", node.name());
            Stats.Totals totals = node.totals();
            if (totals != null) {
                object.put("requests", totals.requests())
                        .put("errors", totals.errors())
                        .put("errorRate", round(totals.errorRate()))
                        .put("rps", round(totals.rps()));
                put(object, "p50Ms", totals.p50Ms());
                put(object, "p95Ms", totals.p95Ms());
                put(object, "p99Ms", totals.p99Ms());
                put(object, "maxMs", totals.maxMs());
                put(object, "apdex", totals.apdex());
                object.put("histogram", longs(totals.histogram())).put("hasJvm", node.hasJvm());
            } else if (!"user".equals(node.kind())) {
                object.put("calls", node.calls()).put("errors", node.errors());
                put(object, "avgMs", node.avgMs());
                put(object, "p95Ms", node.p95Ms());
            }
            nodes.add(object);
        }
        Json.JsonArray edges = Json.arr();
        for (Stats.Edge edge : map.edges()) {
            Json.JsonObject object = Json.obj()
                    .put("from", edge.from())
                    .put("to", edge.to())
                    .put("calls", edge.calls())
                    .put("errors", edge.errors());
            put(object, "avgMs", edge.avgMs());
            put(object, "p95Ms", edge.p95Ms());
            edges.add(object);
        }
        return Json.obj()
                .put("window", window(window))
                .put("nodes", nodes)
                .put("edges", edges);
    }

    /** Arrays, not objects: five thousand points have to stay small on the wire. */
    static Json.JsonArray scatter(List<Stats.ScatterPoint> points) {
        Json.JsonArray array = Json.arr();
        for (Stats.ScatterPoint point : points) {
            array.add(Json.arr()
                    .add(point.start())
                    .add(round(point.durationMs()))
                    .add(point.service())
                    .add(point.endpoint())
                    .add(point.traceId())
                    .add(point.flags()));
        }
        return array;
    }

    static Json.JsonArray metricCatalog(List<MetricQueries.MetricMeta> catalog) {
        Json.JsonArray array = Json.arr();
        for (MetricQueries.MetricMeta metric : catalog) {
            array.add(Json.obj()
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
    static Json.JsonObject series(MetricQueries.SeriesData data, boolean rate) {
        List<MetricPoint> points = data.points();
        long[] t = new long[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            t[i] = points.get(i).at();
            values[i] = points.get(i).value();
        }
        if (rate && data.monotonic()) {
            values = MetricQueries.rate(points);
        }
        Json.JsonObject object = Json.obj()
                .put("service", data.service())
                .put("attributes", attributes(data.attributes()))
                .put("t", longs(t))
                .put("v", doubles(values));
        if ("histogram".equals(data.type())) {
            Json.JsonArray counts = Json.arr();
            double[] p95 = new double[points.size()];
            double[] max = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                counts.add(points.get(i).count());
                p95[i] = points.get(i).percentile(0.95);
                max[i] = points.get(i).max();
            }
            object.put("count", counts).put("p95", doubles(p95)).put("max", doubles(max));
        }
        return object;
    }

    static Json.JsonObject jvm(JvmView jvm) {
        Json.JsonArray pools = Json.arr();
        for (JvmView.Pool pool : jvm.pools()) {
            pools.add(Json.obj()
                    .put("name", pool.name())
                    .put("type", pool.type())
                    .put("t", longs(pool.t()))
                    .put("used", doubles(pool.used())));
        }
        Json.JsonArray gc = Json.arr();
        for (JvmView.Gc collector : jvm.gc()) {
            gc.add(Json.obj()
                    .put("name", collector.name())
                    .put("action", collector.action())
                    .put("t", longs(collector.t()))
                    .put("count", longs(collector.count()))
                    .put("durationMs", doubles(collector.durationMs())));
        }
        Json.JsonArray connectionPools = Json.arr();
        for (JvmView.ConnectionPool pool : jvm.connectionPools()) {
            connectionPools.add(Json.obj()
                    .put("name", pool.name())
                    .put("t", longs(pool.t()))
                    .put("used", doubles(pool.used()))
                    .put("idle", doubles(pool.idle()))
                    .put("max", doubles(pool.max()))
                    .put("pending", doubles(pool.pending())));
        }
        Json.JsonObject runtime = Json.obj().put("jvm", jvm.runtime().jvm());
        put(runtime, "pid", jvm.runtime().pid());
        runtime.put("host", jvm.runtime().host());
        put(runtime, "cpuCount", jvm.runtime().cpuCount());

        return Json.obj()
                .put("service", jvm.service())
                .put("runtime", runtime)
                .put("heap", Json.obj()
                        .put("t", longs(jvm.heap().t()))
                        .put("used", doubles(jvm.heap().used()))
                        .put("committed", doubles(jvm.heap().committed()))
                        .put("limit", doubles(jvm.heap().limit())))
                .put("nonHeap", Json.obj()
                        .put("t", longs(jvm.nonHeap().t()))
                        .put("used", doubles(jvm.nonHeap().used()))
                        .put("committed", doubles(jvm.nonHeap().committed())))
                .put("pools", pools)
                .put("gc", gc)
                .put("threads", Json.obj()
                        .put("t", longs(jvm.threads().t()))
                        .put("count", doubles(jvm.threads().count()))
                        .put("daemon", doubles(jvm.threads().daemon())))
                .put("cpu", Json.obj()
                        .put("t", longs(jvm.cpu().t()))
                        .put("utilization", doubles(jvm.cpu().utilization()))
                        .put("systemLoad1m", doubles(jvm.cpu().systemLoad1m())))
                .put("classes", Json.obj()
                        .put("t", longs(jvm.classes().t()))
                        .put("loaded", doubles(jvm.classes().loaded())))
                .put("connectionPools", connectionPools);
    }

    static Json.JsonObject error(@Nullable String message) {
        return Json.obj().put("error", message == null ? "Bad request" : message);
    }

    // --- the agent interface --------------------------------------------------

    static Json.JsonObject mark(Marks.Mark mark) {
        return Json.obj()
                .put("id", mark.id())
                .put("at", mark.at())
                .put("name", mark.name())
                .put("service", mark.service())
                .put("note", mark.note());
    }

    static Json.JsonArray marks(List<Marks.Mark> marks) {
        Json.JsonArray array = Json.arr();
        marks.forEach(mark -> array.add(mark(mark)));
        return array;
    }

    static Json.JsonObject finding(Findings.Finding finding) {
        Json.JsonObject numbers = Json.obj();
        finding.numbers().forEach((key, value) -> any(numbers, key, value));
        return Json.obj()
                .put("id", finding.id())
                .put("kind", finding.kind())
                .put("severity", finding.severity())
                .put("service", finding.service())
                .put("title", finding.title())
                .put("why", finding.why())
                .put("subject", Json.obj()
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
                .put("code", strings(finding.code()))
                .put("traces", strings(finding.traces()))
                .put("schema", schema(finding.schema()))
                .put("ack", finding.ack() == null ? null
                        : Json.obj().put("at", finding.ack().at()).put("note", finding.ack().note()));
    }

    static Json.JsonArray findings(List<Findings.Finding> findings) {
        Json.JsonArray array = Json.arr();
        findings.forEach(finding -> array.add(finding(finding)));
        return array;
    }

    static Json.JsonObject ack(Acks.Ack ack) {
        return Json.obj()
                .put("findingId", ack.findingId())
                .put("at", ack.at())
                .put("note", ack.note());
    }

    static Json.JsonArray acks(List<Acks.Ack> acks) {
        Json.JsonArray array = Json.arr();
        acks.forEach(ack -> array.add(ack(ack)));
        return array;
    }

    /**
     * A finding's {@code numbers}, whose keys are the kind's own.
     *
     * <p>They are data rather than schema — every kind names different ones — so
     * they are written by the type of the value, which is the one place in this file
     * that does not read like the contract, because the contract itself says
     * "kind-specific".
     */
    private static void any(Json.JsonObject object, String key, Object value) {
        switch (value) {
            case null -> object.putNull(key);
            case String text -> object.put(key, text);
            case Double number -> put(object, key, number.doubleValue());
            case Float number -> put(object, key, number.doubleValue());
            case Number number -> object.put(key, number.longValue());
            case Boolean flag -> object.put(key, flag.booleanValue());
            case Map<?, ?> map -> {
                // A nested object, as a slow-endpoint's hotSpan is.
                Json.JsonObject inner = Json.obj();
                map.forEach((name, each) -> any(inner, String.valueOf(name), each));
                object.put(key, inner);
            }
            case List<?> list -> {
                Json.JsonArray array = Json.arr();
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        Json.JsonObject inner = Json.obj();
                        map.forEach((name, each) -> any(inner, String.valueOf(name), each));
                        array.add(inner);
                    } else {
                        array.add(String.valueOf(element));
                    }
                }
                object.put(key, array);
            }
            default -> object.put(key, String.valueOf(value));
        }
    }

    static Json.JsonObject comparison(Compare.Comparison comparison) {
        Json.JsonArray endpoints = Json.arr();
        for (Compare.EndpointDiff diff : comparison.endpoints()) {
            endpoints.add(Json.obj()
                    .put("endpointId", diff.endpointId())
                    .put("service", diff.service())
                    .put("name", diff.name())
                    .put("before", side(diff.before()))
                    .put("after", side(diff.after()))
                    .put("verdict", diff.verdict()));
        }
        Json.JsonArray queries = Json.arr();
        for (Compare.QueryDiff diff : comparison.queries()) {
            queries.add(Json.obj()
                    .put("queryId", diff.queryId())
                    .put("service", diff.service())
                    .put("statement", diff.statement())
                    .put("before", querySide(diff.before()))
                    .put("after", querySide(diff.after()))
                    .put("verdict", diff.verdict()));
        }
        Json.JsonArray errors = Json.arr();
        for (Compare.ErrorDiff diff : comparison.errors()) {
            errors.add(Json.obj()
                    .put("errorId", diff.errorId())
                    .put("service", diff.service())
                    .put("type", diff.type())
                    .put("message", diff.message())
                    .put("before", diff.before())
                    .put("after", diff.after())
                    .put("verdict", diff.verdict()));
        }
        return Json.obj()
                .put("before", Json.obj()
                        .put("from", comparison.before().from())
                        .put("to", comparison.before().to()))
                .put("after", Json.obj()
                        .put("from", comparison.after().from())
                        .put("to", comparison.after().to()))
                .put("totals", Json.obj()
                        .put("before", totals(comparison.beforeTotals()))
                        .put("after", totals(comparison.afterTotals())))
                .put("endpoints", endpoints)
                .put("queries", queries)
                .put("errors", errors);
    }

    private static Json.@Nullable JsonValue side(Compare.@Nullable Side side) {
        if (side == null) {
            return null;
        }
        Json.JsonObject object = Json.obj()
                .put("calls", side.calls())
                .put("errors", side.errors());
        put(object, "p50Ms", side.p50Ms());
        put(object, "p95Ms", side.p95Ms());
        put(object, "maxMs", side.maxMs());
        put(object, "dbCallsPerRequest", side.dbCallsPerRequest());
        put(object, "dbMsPerRequest", side.dbMsPerRequest());
        return object;
    }

    private static Json.@Nullable JsonValue querySide(Compare.@Nullable QuerySide side) {
        if (side == null) {
            return null;
        }
        Json.JsonObject object = Json.obj().put("calls", side.calls());
        put(object, "callsPerRequest", side.callsPerRequest());
        put(object, "p95Ms", side.p95Ms());
        put(object, "totalMs", side.totalMs());
        return object;
    }

    static Json.JsonObject checkResult(Check.CheckResult result) {
        Json.JsonArray checks = Json.arr();
        for (Check.RuleCheck check : result.checks()) {
            Json.JsonObject object = Json.obj().put("rule", check.rule());
            put(object, "limit", check.limit());
            put(object, "actual", check.actual());
            checks.add(object.put("pass", check.pass()).put("detail", check.detail()));
        }
        Json.JsonObject object = Json.obj();
        if (result.pass() == null) {
            object.putNull("pass");
        } else {
            object.put("pass", result.pass().booleanValue());
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
    static Json.JsonObject sqlResult(ReadOnlyQuery.Result result) {
        Json.JsonArray rows = Json.arr();
        for (List<Object> row : result.rows()) {
            Json.JsonArray cells = Json.arr();
            for (Object cell : row) {
                cell(cells, cell);
            }
            rows.add(cells);
        }
        return Json.obj()
                .put("columns", Json.arr().addAll(result.columns()))
                .put("rows", rows)
                .put("rowCount", result.rows().size())
                .put("truncated", result.truncated())
                .put("elapsedMs", result.elapsedMs());
    }

    /** A cell stays the type the store holds it as; anything else is its text. */
    private static void cell(Json.JsonArray cells, @Nullable Object value) {
        switch (value) {
            case null -> cells.add((Json.JsonValue) null);
            case Boolean flag -> cells.add(flag.booleanValue());
            case Long number -> cells.add(number.longValue());
            case Double number -> {
                if (number.isNaN() || number.isInfinite()) {
                    cells.add((Json.JsonValue) null);
                } else {
                    cells.add(number.doubleValue());
                }
            }
            default -> cells.add(String.valueOf(value));
        }
    }
}
