package net.benelog.spidersense.api;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

import net.benelog.spidersense.ingest.ErrorBody;
import net.benelog.spidersense.ingest.RequestBody;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Importer;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonObject;
import net.benelog.silkjson.JsonArray;
import net.benelog.silkjson.JsonException;

/**
 * The status and control endpoints ({@code status}, {@code clear}, {@code export} and
 * {@code import}) and what every {@code /api} route shares: the no-store filter and the error
 * pages, one for every error status but the server's own 404.
 *
 * <p>Each class of this package registers its own routes as one visible list, which is Spider
 * Silk's rule, and {@code SpiderSenseServer.assemble} lists the classes.
 */
public final class StatusApi {

    private final Config config;
    private final Store store;
    private final Queries queries;
    private final Reports reports;
    private final Params params;
    private final IntSupplier port;
    private final long startedAt;

    public StatusApi(Config config, Store store, Queries queries, Reports reports, Params params,
            IntSupplier port) {
        this.config = config;
        this.store = store;
        this.queries = queries;
        this.reports = reports;
        this.params = params;
        this.startedAt = reports.now();
        this.port = port;
    }

    public void register(App app) {
        app.get("/api/status", "What this server is and how much it holds", this::status);
        app.delete("/api/data", "Empty every window", this::clear);
        app.get("/api/export", "The window as a JSON download", this::export);
        app.post("/api/import", "An exported document, written back", this::importDocument);

        // Every API answer is live data; a browser that cached it would show a frozen dashboard.
        // Spider Silk types JSON as bare application/json, and the API states its charset, as the
        // refusals Jetty answers before routing (JsonErrorHandler) do (api.adoc#conventions).
        app.responseFilter((req, res) -> {
            WebResponse typed = "application/json".equals(res.header("Content-Type"))
                    ? res.contentType("application/json; charset=utf-8") : res;
            return req.path().startsWith("/api/") ? typed.header("Cache-Control", "no-store") : typed;
        });

        // Every error is {"error": ...} (api.adoc#conventions), whichever status the framework or a
        // handler ended on: a 400, a 405, a 500, and the 413 of a body past Spider Silk's limit,
        // which would otherwise go out as its text/plain line. The 404 is the server's own, since it
        // also serves the single page (SpiderSenseServer.notFound).
        for (HttpStatus status : HttpStatus.values()) {
            if (status.code() >= 400 && status != HttpStatus.NOT_FOUND) {
                app.statusPage(status, req ->
                        WebResponse.json(ErrorBody.json(req.errorMessage(), ErrorBody.reason(status))));
            }
        }
    }

    public WebResponse status(WebRequest req) {
        return Params.answer(req, report());
    }

    /** What {@code /api/status} answers, which the MCP {@code status} tool answers too (mcp.adoc#tools). */
    public Reports.Report report() {
        return reports.status(config.mode(), config.baseUrl(port.getAsInt()), startedAt);
    }

    public WebResponse clear(WebRequest req) {
        store.clear();
        return WebResponse.noContent();
    }

    /**
     * The escape hatch from "in memory is fine": one trace as a file that can be
     * attached to a bug report, or — with no {@code traceId} — the whole window as
     * the session document an import reads back (cli.adoc#export-import).
     *
     * <p>The window form is streamed rather than built: a session is as large as
     * the retention allows, and holding every row of it as objects in order to
     * serialise them once would cost the monitored application's heap for nothing.
     */
    public WebResponse export(WebRequest req) {
        String traceId = req.queryParamOrNull("traceId");
        if (traceId == null) {
            Window window = params.window(req);
            String service = Params.service(req);
            return WebResponse
                    .stream("application/json", out -> reports.export(window, service, out))
                    .attachment(Reports.exportFilename(window));
        }
        JsonArray traces = Json.array();
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace != null) {
            traces.add(Codecs.trace(trace, store.tingles()));
        }
        return WebResponse.json(Json.object().put("traces", traces))
                .attachment("spider-sense-traces.json");
    }

    /**
     * The largest document an import reads, once gunzipped. The whole document is
     * held as text and as a tree, in agent mode in the monitored application's heap.
     */
    private static final int MAX_IMPORT = 256 * 1024 * 1024;

    /**
     * That document back into the store.
     *
     * <p>The body is parsed whole rather than streamed: a session file is tens of
     * megabytes at most, the import is one transaction anyway, and half a document
     * would leave nothing to answer with. A document of another schema version is
     * a {@code 400} naming both, because there is no honest way to write rows of a
     * shape this version does not have. A value the store refuses, one too long for
     * its column, is a {@code 400} too: the file is at fault, not the server.
     */
    public WebResponse importDocument(WebRequest req) {
        JsonObject document;
        try {
            document = Json.parse(body(req)).asObject();
        } catch (RequestBody.TooLarge e) {
            return ErrorBody.response(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), "Content too large");
        } catch (UncheckedIOException e) {
            // A body that does not gunzip: the reason ("Not in GZIP format"), not the class name.
            Throwable cause = e.getCause();
            return Params.badRequest(req, "Undecodable import document: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (RuntimeException e) {
            return Params.badRequest(req, "Undecodable import document: " + e.getMessage());
        }
        try {
            return Params.answer(req, reports.imported(reports.importDocument(document)));
        } catch (Importer.WrongSchema | Importer.BadDocument e) {
            return Params.badRequest(req, e.getMessage());
        } catch (JsonException e) {
            // A row of the wrong shape (a span that is not an object, a number that
            // is a string) fails only once the import reads it, and the transaction
            // has rolled back by the time it reaches here.
            return Params.badRequest(req, "Undecodable import document: " + e.getMessage());
        }
    }

    /** The body, gunzipped when {@code Content-Encoding} says so (api.adoc#ingest). */
    private static String body(WebRequest req) {
        return new String(RequestBody.read(req, MAX_IMPORT), StandardCharsets.UTF_8);
    }
}
