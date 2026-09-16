package net.benelog.spidersense.api;

import java.util.ArrayList;
import java.util.List;

import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Stats;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * The read endpoints about traffic: the overview, services, endpoints, traces,
 * the XLog scatter, queries, errors and logs.
 *
 * <p>Each handler is the same three steps — read the window and the filters,
 * ask {@link Queries} for records, render with {@link Codecs} — so what an
 * endpoint answers is visible without following anything.
 */
public final class TraceApi {

    private static final int DETAIL_TRACES = 20;
    private static final int TOP_N = 10;

    private final Store store;
    private final Queries queries;

    public TraceApi(Store store, Queries queries) {
        this.store = store;
        this.queries = queries;
    }

    public void register(App app) {
        app.get("/api/overview", "Totals, services and tingles over the window", this::overview);
        app.get("/api/services", "Every service seen", this::services);
        app.get("/api/services/{name}", "One service in detail", this::service);
        app.get("/api/endpoints", "Endpoint statistics across services", this::endpoints);
        app.get("/api/endpoints/{endpointId}", "One endpoint in detail", this::endpoint);
        app.get("/api/traces", "Trace list", this::traces);
        app.get("/api/traces/{traceId}", "One trace with its spans and logs", this::trace);
        app.get("/api/xlog", "One point per entry span", this::xlog);
        app.get("/api/queries", "Database statements grouped", this::queries);
        app.get("/api/queries/{queryId}", "One query group in detail", this::query);
        app.get("/api/errors", "Errors grouped", this::errors);
        app.get("/api/errors/{errorId}", "One error group in detail", this::error);
        app.get("/api/logs", "Log lines", this::logs);
    }

    public WebResponse overview(WebRequest req) {
        Window window = Params.window(req);
        return WebResponse.json(Json.obj()
                .put("window", Codecs.window(window))
                .put("totals", Codecs.totals(queries.totals(window, null)))
                .put("services", Codecs.serviceSummaries(queries.services(window)))
                .put("tingles", Codecs.tingles(queries.tingles(window, 50)))
                .put("series", Codecs.overviewSeries(queries.buckets(window, null, null))));
    }

    public WebResponse services(WebRequest req) {
        Window window = Params.window(req);
        return WebResponse.json(Json.obj()
                .put("services", Codecs.serviceSummaries(queries.services(window))));
    }

    public WebResponse service(WebRequest req) {
        String name = req.pathParam("name");
        Window window = Params.window(req);
        Stats.ServiceSummary summary = queries.service(name, window);
        if (summary == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such service: " + name);
        }
        Json.JsonObject resource = Json.obj();
        queries.resource(name).forEach(resource::put);
        return WebResponse.json(Json.obj()
                .put("service", Codecs.serviceSummary(summary))
                .put("resource", resource)
                .put("window", Codecs.window(window))
                .put("series", Codecs.serviceSeries(queries.buckets(window, name, null)))
                .put("endpoints", Codecs.endpoints(queries.endpoints(window, name, null)))
                .put("queries", Codecs.queries(queries.queries(window, name, "total", TOP_N, null)))
                .put("errors", Codecs.errorGroups(queries.errors(window, name, TOP_N, null)))
                .put("dependencies", Codecs.dependencies(queries.dependencies(name, window))));
    }

    public WebResponse endpoints(WebRequest req) {
        Window window = Params.window(req);
        return WebResponse.json(Json.obj().put("endpoints",
                Codecs.endpoints(queries.endpoints(window, Params.service(req), null))));
    }

    public WebResponse endpoint(WebRequest req) {
        String endpointId = req.pathParam("endpointId");
        Window window = Params.window(req);
        List<Stats.EndpointStats> found = queries.endpoints(window, null, endpointId);
        if (found.isEmpty()) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such endpoint in this window: " + endpointId);
        }
        Stats.EndpointStats endpoint = found.get(0);
        return WebResponse.json(Json.obj()
                .put("endpoint", Codecs.endpoint(endpoint))
                .put("series", Codecs.serviceSeries(queries.buckets(window, null, endpointId)))
                .put("queries", Codecs.queries(calledFrom(window, endpoint)))
                .put("errors", Codecs.errorGroups(failedIn(window, endpoint)))
                .put("traces", Codecs.traceSummaries(queries.tracesContaining(window,
                        "endpoint_id = ?", endpointId, DETAIL_TRACES, true)))
                .put("recent", Codecs.traceSummaries(queries.tracesContaining(window,
                        "endpoint_id = ?", endpointId, DETAIL_TRACES, false))));
    }

    /** The queries whose callers include this endpoint. */
    private List<Stats.QueryStats> calledFrom(Window window, Stats.EndpointStats endpoint) {
        List<Stats.QueryStats> matching = new ArrayList<>();
        for (Stats.QueryStats query : queries.queries(window, null, "total", 100, null)) {
            for (Stats.Caller caller : query.callers()) {
                if (caller.endpoint().equals(endpoint.name())) {
                    matching.add(query);
                    break;
                }
            }
        }
        return matching;
    }

    private List<Stats.ErrorGroup> failedIn(Window window, Stats.EndpointStats endpoint) {
        List<Stats.ErrorGroup> matching = new ArrayList<>();
        for (Stats.ErrorGroup group : queries.errors(window, null, 100, null)) {
            for (Stats.EndpointCount count : group.endpoints()) {
                if (count.name().equals(endpoint.name())) {
                    matching.add(group);
                    break;
                }
            }
        }
        return matching;
    }

    public WebResponse traces(WebRequest req) {
        Queries.TraceFilter filter = new Queries.TraceFilter(
                Params.window(req),
                Params.service(req),
                req.queryParamOrNull("endpointId"),
                Params.optionalLong(req, "minMs"),
                Params.optionalLong(req, "maxMs"),
                req.queryParamOrNull("status"),
                req.queryParamOrNull("q"),
                Params.optionalLong(req, "before"),
                Params.limit(req, 50, 1000));
        return WebResponse.json(Json.obj()
                .put("traces", Codecs.traceSummaries(queries.traces(filter)))
                .put("total", queries.traceTotal(filter))
                .put("window", Codecs.window(filter.window())));
    }

    public WebResponse trace(WebRequest req) {
        String traceId = req.pathParam("traceId");
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such trace: " + traceId);
        }
        return WebResponse.json(Codecs.trace(trace, store.tingles()));
    }

    public WebResponse xlog(WebRequest req) {
        Window window = Params.window(req);
        int limit = Params.limit(req, 5000, 50_000);
        List<Stats.XlogPoint> points = queries.xlog(window, Params.service(req),
                req.queryParamOrNull("endpointId"), limit);
        return WebResponse.json(Json.obj()
                .put("window", Codecs.window(window))
                .put("truncated", points.size() >= limit)
                .put("points", Codecs.xlog(points)));
    }

    public WebResponse queries(WebRequest req) {
        Window window = Params.window(req);
        return WebResponse.json(Json.obj().put("queries", Codecs.queries(queries.queries(window,
                Params.service(req), req.queryParamOrNull("sort"), Params.limit(req, 100, 1000), null))));
    }

    public WebResponse query(WebRequest req) {
        String queryId = req.pathParam("queryId");
        Window window = Params.window(req);
        List<Stats.QueryStats> found = queries.queries(window, null, "total", 1, queryId);
        if (found.isEmpty()) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such query in this window: " + queryId);
        }
        Stats.Buckets buckets = queries.queryBuckets(window, queryId);
        return WebResponse.json(Json.obj()
                .put("query", Codecs.query(found.get(0)))
                .put("series", Json.obj()
                        .put("t", Codecs.longs(buckets.t()))
                        .put("calls", Codecs.longs(buckets.requests()))
                        .put("p95Ms", Codecs.doubles(buckets.p95Ms(), buckets.requests())))
                .put("traces", Codecs.traceSummaries(queries.tracesContaining(window,
                        "query_id = ?", queryId, DETAIL_TRACES, true))));
    }

    public WebResponse errors(WebRequest req) {
        Window window = Params.window(req);
        return WebResponse.json(Json.obj().put("errors", Codecs.errorGroups(
                queries.errors(window, Params.service(req), Params.limit(req, 100, 1000), null))));
    }

    public WebResponse error(WebRequest req) {
        String errorId = req.pathParam("errorId");
        Window window = Params.window(req);
        List<Stats.ErrorGroup> found = queries.errors(window, null, 1, errorId);
        if (found.isEmpty()) {
            throw new HttpException(HttpStatus.NOT_FOUND, "No such error in this window: " + errorId);
        }
        Stats.Buckets buckets = queries.errorBuckets(window, errorId);
        return WebResponse.json(Json.obj()
                .put("error", Codecs.errorGroup(found.get(0)))
                .put("series", Json.obj()
                        .put("t", Codecs.longs(buckets.t()))
                        .put("count", Codecs.longs(buckets.requests())))
                .put("traces", Codecs.traceSummaries(queries.tracesContaining(window,
                        "error_id = ?", errorId, DETAIL_TRACES, false))));
    }

    public WebResponse logs(WebRequest req) {
        Queries.LogFilter filter = new Queries.LogFilter(
                Params.window(req),
                Params.service(req),
                req.queryParamOrNull("severity"),
                req.queryParamOrNull("q"),
                req.queryParamOrNull("traceId"),
                Params.optionalLong(req, "before"),
                Params.limit(req, 200, 5000));
        return WebResponse.json(Json.obj()
                .put("logs", Codecs.logs(queries.logs(filter)))
                .put("total", queries.logTotal(filter)));
    }
}
