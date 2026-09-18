package net.benelog.spidersense.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.zip.GZIPInputStream;

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
    private final Reports reports;
    private final Params params;
    private final IntSupplier port;
    private final long startedAt = System.currentTimeMillis();

    public ApiRoutes(Config config, Store store, Queries queries, Reports reports, IntSupplier port) {
        this.config = config;
        this.store = store;
        this.queries = queries;
        this.reports = reports;
        this.params = new Params(reports.selectors());
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
        return Params.answer(req,
                reports.status(config.mode(), config.endpoint(port.getAsInt()), startedAt));
    }

    public WebResponse clear(WebRequest req) {
        store.clear();
        return WebResponse.noContent();
    }

    /**
     * The escape hatch from "in memory is fine": one trace as a file that can be
     * attached to a bug report, or — with no {@code traceId} — the whole window as
     * the session document an import reads back (agent.md).
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
     * That document back into the store.
     *
     * <p>The body is parsed whole rather than streamed: a session file is tens of
     * megabytes at most, the import is one transaction anyway, and half a document
     * would leave nothing to answer with. A document of another schema version is
     * a {@code 400} naming both, because there is no honest way to write rows of a
     * shape this version does not have.
     */
    public WebResponse importDocument(WebRequest req) {
        Json.JsonObject document;
        try {
            document = Json.parse(body(req)).asObject();
        } catch (RuntimeException e) {
            return Params.problem(req, "Undecodable import document: " + e.getMessage());
        }
        try {
            return Params.answer(req, reports.imported(reports.importDocument(document)));
        } catch (Importer.WrongSchema e) {
            return Params.problem(req, e.getMessage());
        }
    }

    /** The body, gunzipped when {@code Content-Encoding} says so (api.md). */
    private static String body(WebRequest req) {
        String encoding = req.header("Content-Encoding");
        boolean gzipped = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
        try (InputStream in = gzipped ? new GZIPInputStream(req.bodyStream()) : req.bodyStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the import body", e);
        }
    }
}
