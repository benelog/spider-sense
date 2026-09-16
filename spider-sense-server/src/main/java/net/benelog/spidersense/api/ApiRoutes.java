package net.benelog.spidersense.api;

import java.util.List;
import java.util.function.IntSupplier;

import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

/**
 * The status and control endpoints, and the place where the whole {@code /api}
 * surface is registered.
 *
 * <p>The routing table is one visible list, which is Spider Silk's rule and also
 * the fastest way to answer "what does the UI actually call?".
 */
public final class ApiRoutes {

    public static final String NAME = "Spider Sense";
    public static final String VERSION = "0.1.0";

    private final Config config;
    private final Store store;
    private final Queries queries;
    private final IntSupplier port;
    private final long startedAt = System.currentTimeMillis();

    public ApiRoutes(Config config, Store store, Queries queries, IntSupplier port) {
        this.config = config;
        this.store = store;
        this.queries = queries;
        this.port = port;
    }

    public void register(App app) {
        app.get("/api/status", "What this server is and how much it holds", this::status);
        app.delete("/api/data", "Empty every window", this::clear);
        app.get("/api/export", "Traces as a JSON download", this::export);

        // Every API answer is live data; a browser that cached it would show a frozen dashboard.
        app.responseFilter((req, res) ->
                req.path().startsWith("/api/") ? res.header("Cache-Control", "no-store") : res);

        app.error(HttpStatus.BAD_REQUEST, req ->
                WebResponse.json(Codecs.error(message(req, "Bad request"))));
        app.error(HttpStatus.METHOD_NOT_ALLOWED, req ->
                WebResponse.json(Codecs.error(message(req, "Method not allowed"))));
        app.error(HttpStatus.INTERNAL_SERVER_ERROR, req ->
                WebResponse.json(Codecs.error(message(req, "Internal server error"))));
    }

    private static String message(WebRequest req, String fallback) {
        String message = req.errorMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    public WebResponse status(WebRequest req) {
        String endpoint = config.endpoint(port.getAsInt());
        Database.Storage storage = store.database().storage();
        return WebResponse.json(Json.obj()
                .put("name", NAME)
                .put("version", VERSION)
                .put("mode", config.mode())
                .put("startedAt", startedAt)
                .put("now", System.currentTimeMillis())
                .put("endpoint", endpoint)
                .put("otlp", Json.obj()
                        .put("traces", endpoint + "/v1/traces")
                        .put("metrics", endpoint + "/v1/metrics")
                        .put("logs", endpoint + "/v1/logs"))
                .put("embeddedService", store.services().embeddedService())
                .put("thresholds", Json.obj()
                        .put("slowRequestMs", store.tingles().slowRequestMs())
                        .put("slowQueryMs", store.tingles().slowQueryMs())
                        // The scale of every histogram, so the UI builds its legend from here.
                        .put("responseBucketsMs", Codecs.longs(queries.responseBuckets().bounds())))
                .put("retention", Json.obj().put("hours", config.retentionHours()))
                .put("storage", Json.obj()
                        .put("url", storage.url())
                        .put("path", storage.path())
                        .put("sizeBytes", storage.sizeBytes())
                        .put("fallback", storage.fallback())
                        .put("fallbackReason", storage.fallbackReason())
                        .put("droppedBatches", store.writer().droppedBatches())
                        .put("queued", store.writer().queued()))
                .put("counts", Json.obj()
                        .put("spans", queries.spanCount())
                        .put("traces", queries.traceCount())
                        .put("logs", queries.logCount())
                        .put("metricSeries", queries.metricSeriesCount())
                        .put("services", store.services().count()))
                .put("oldest", Json.obj()
                        .put("span", queries.oldestSpan())
                        .put("log", queries.oldestLog())));
    }

    public WebResponse clear(WebRequest req) {
        store.clear();
        return WebResponse.noContent();
    }

    /**
     * The escape hatch from "in memory is fine": one trace, or a window of them,
     * as a file that can be attached to a bug report.
     */
    public WebResponse export(WebRequest req) {
        String traceId = req.queryParamOrNull("traceId");
        List<String> traceIds;
        if (traceId != null) {
            traceIds = List.of(traceId);
        } else {
            Window window = Params.window(req);
            Queries.TraceFilter filter = new Queries.TraceFilter(window, Params.service(req), null,
                    null, null, null, null, null, Params.limit(req, 1000, 10_000));
            traceIds = queries.traces(filter).stream().map(t -> t.traceId()).toList();
        }
        Json.JsonArray traces = Json.arr();
        for (String id : traceIds) {
            Queries.TraceDetail trace = queries.trace(id);
            if (trace != null) {
                traces.add(Codecs.trace(trace, store.tingles()));
            }
        }
        return WebResponse.json(Json.obj().put("traces", traces))
                .attachment("spider-sense-traces.json");
    }
}
