package net.benelog.spidersense.api;

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
import net.benelog.spidersilk.json.Json;

/**
 * The status and control endpoints ({@code status}, {@code clear}, {@code export} and
 * {@code import}) and what every {@code /api} route shares: the no-store filter and the error
 * pages.
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

    public StatusApi(Config config, Store store, Queries queries, Reports reports, IntSupplier port) {
        this.config = config;
        this.store = store;
        this.queries = queries;
        this.reports = reports;
        this.params = new Params(reports.selectors());
        this.startedAt = reports.now();
        this.port = port;
    }

    public void register(App app) {
        app.get("/api/status", "What this server is and how much it holds", this::status);
        app.delete("/api/data", "Empty every window", this::clear);
        app.get("/api/export", "The window as a JSON download", this::export);
        app.post("/api/import", "An exported document, written back", this::importDocument);

        // Every API answer is live data; a browser that cached it would show a frozen dashboard.
        app.responseFilter((req, res) ->
                req.path().startsWith("/api/") ? res.header("Cache-Control", "no-store") : res);

        app.error(HttpStatus.BAD_REQUEST, req ->
                WebResponse.json(ErrorBody.json(req.errorMessage(), "Bad request")));
        app.error(HttpStatus.METHOD_NOT_ALLOWED, req ->
                WebResponse.json(ErrorBody.json(req.errorMessage(), "Method not allowed")));
        app.error(HttpStatus.INTERNAL_SERVER_ERROR, req ->
                WebResponse.json(ErrorBody.json(req.errorMessage(), "Internal server error")));
    }

    public WebResponse status(WebRequest req) {
        return Params.answer(req,
                reports.status(config.mode(), config.baseUrl(port.getAsInt()), startedAt));
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
        Json.JsonArray traces = Json.arr();
        Queries.TraceDetail trace = queries.trace(traceId);
        if (trace != null) {
            traces.add(Codecs.trace(trace, store.tingles()));
        }
        return WebResponse.json(Json.obj().put("traces", traces))
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
        Json.JsonObject document;
        try {
            document = Json.parse(body(req)).asObject();
        } catch (RequestBody.TooLarge e) {
            return ErrorBody.response(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), "Content too large");
        } catch (RuntimeException e) {
            return Params.problem(req, "Undecodable import document: " + e.getMessage());
        }
        try {
            return Params.answer(req, reports.imported(reports.importDocument(document)));
        } catch (Importer.WrongSchema | Importer.BadDocument e) {
            return Params.problem(req, e.getMessage());
        } catch (Json.JsonException e) {
            // A row of the wrong shape (a span that is not an object, a number that
            // is a string) fails only once the import reads it, and the transaction
            // has rolled back by the time it reaches here.
            return Params.problem(req, "Undecodable import document: " + e.getMessage());
        }
    }

    /** The body, gunzipped when {@code Content-Encoding} says so (api.adoc#ingest). */
    private static String body(WebRequest req) {
        return new String(RequestBody.read(req, MAX_IMPORT), StandardCharsets.UTF_8);
    }
}
