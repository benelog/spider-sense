package net.benelog.spidersense.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import net.benelog.spidersense.api.AgentApi;
import net.benelog.spidersense.api.EventsApi;
import net.benelog.spidersense.api.McpApi;
import net.benelog.spidersense.api.MetricsApi;
import net.benelog.spidersense.api.Reports;
import net.benelog.spidersense.api.SourceApi;
import net.benelog.spidersense.api.StatusApi;
import net.benelog.spidersense.api.TrafficApi;
import net.benelog.spidersense.ingest.ErrorBody;
import net.benelog.spidersense.ingest.OtlpDecoder;
import net.benelog.spidersense.ingest.OtlpReceiver;
import net.benelog.spidersense.mcp.McpServer;
import net.benelog.spidersense.mcp.McpTools;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.query.Queries;
import net.benelog.spidersense.source.SourceRoots;
import net.benelog.spidersense.store.IngestCap;
import net.benelog.spidersense.store.Store;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.server.JettyServer;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.jspecify.annotations.Nullable;

/**
 * The collector and UI: an OTLP/HTTP receiver, an H2-backed store and a JSON API,
 * all on one port.
 *
 * <p>{@link #main(String[])} starts it and returns. The launcher decides whether
 * to keep the JVM alive: standalone joins, agent mode does not, because the
 * monitored application's own {@code main} must still be able to return. Every
 * thread this class or the store creates is therefore a daemon thread in agent
 * mode, and Jetty's shutdown hook is left to whoever owns the lifecycle.
 *
 * <p>The object graph is assembled here by calling constructors, in the order a
 * reader would ask about them. There is no container, which is Spider Silk's rule
 * and also why this method is the whole architecture on one screen.
 */
public final class SpiderSenseServer implements AutoCloseable {

    private static final String INDEX = "public/index.html";

    private final App app;
    private final Store store;
    private final Config config;

    private SpiderSenseServer(App app, Store store, Config config) {
        this.app = app;
        this.store = store;
        this.config = config;
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);
        SpiderSenseServer server = start(config);
        System.out.println("Spider Sense (" + config.mode() + "): " + config.baseUrl(server.port()));
        if (!config.agentMode()) {
            server.join();
        }
    }

    /**
     * The object graph, built but not yet serving.
     *
     * <p>{@code boundPort} is what the self-monitoring drop rule compares against;
     * it is mutable because {@code --port=0} is only resolved once Jetty has bound.
     */
    public record Assembly(App app, Store store, Config config, AtomicInteger boundPort) {
    }

    /** Builds everything and registers every route, without binding a port. */
    public static Assembly assemble(Config config) {
        return assemble(config, System::currentTimeMillis);
    }

    /**
     * The same with the time given, which the store, the answers and the ingest all read: a test
     * that pins it can put its telemetry at fixed instants and still ask for {@code since=5m}.
     */
    public static Assembly assemble(Config config, LongSupplier clock) {
        Store store = new Store(config.jdbcUrl(), config.databaseFile(), config.retentionHours(),
                config.slowRequestMs(), config.slowQueryMs(), config.embeddedService(),
                config.ignoreEndpoints(), config.retentionSpans(),
                IngestCap.of(config.maxSpansPerSecond()), clock);
        AtomicInteger boundPort = new AtomicInteger(config.port());

        Queries queries = new Queries(store.sql(), store.tingles(), store.services());
        MetricQueries metrics = new MetricQueries(store.sql());
        Reports reports = new Reports(config, store, boundPort::get);

        App app = new App();
        app.beforeRequest(req -> LocalRequests.refuseForeign(req, config.host()));
        new OtlpReceiver(new OtlpDecoder(store, boundPort::get), store.writer(), config.awaitWrites())
                .register(app);
        new StatusApi(config, store, queries, reports, boundPort::get).register(app);
        new TrafficApi(queries, reports).register(app);
        new MetricsApi(metrics, store.services(), reports.selectors()).register(app);
        new AgentApi(reports).register(app);
        new SourceApi(SourceRoots.of(config.sourceDirs(), Path.of(""))).register(app);
        new McpApi(new McpServer(McpTools.TOOLS, new McpTools(reports), Version.CURRENT)).register(app);
        new EventsApi(store, queries).register(app);
        app.error(HttpStatus.NOT_FOUND, SpiderSenseServer::notFound);
        app.server((a, port) -> server(a, port, config));
        return new Assembly(app, store, config, boundPort);
    }

    /** Builds the store and the app, binds the port, and returns once it is bound. */
    public static SpiderSenseServer start(Config config) {
        Assembly assembly = assemble(config);
        try {
            assembly.app().start(config.port());
        } catch (RuntimeException e) {
            assembly.store().close();
            throw e;
        }
        assembly.boundPort().set(assembly.app().port());
        return new SpiderSenseServer(assembly.app(), assembly.store(), config);
    }

    private static JettyServer server(App app, int port, Config config) {
        // No handler reads a session, so the container's session manager and its
        // housekeeping thread are pure cost.
        JettyServer server = new JettyServer(app).port(port).host(config.host()).sessions(false)
                // A service name or a finding id may hold a '/' or a '%', which a link carries
                // as %2F or %25 inside one path segment; Jetty refuses both by default.
                .customizeHttpConfiguration(http -> http.setUriCompliance(UriCompliance.DEFAULT
                        .with("spider-sense", UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR,
                                UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)))
                .customizeContext(context -> context.getServletHandler().setDecodeAmbiguousURIs(true))
                // What it still refuses before routing (%5C, %2E%2E) answers in the API's error
                // shape rather than as Jetty's HTML page.
                .customizeServer(jetty -> jetty.setErrorHandler(new JsonErrorHandler()));
        if (config.agentMode()) {
            // Inside someone else's JVM: our threads must never be what keeps it alive,
            // and the lifecycle belongs to the launcher, not to a shutdown hook of ours.
            QueuedThreadPool pool = new QueuedThreadPool();
            pool.setName("spider-sense-jetty");
            pool.setDaemon(true);
            server.threadPool(pool)
                    .shutdownHook(false)
                    .customizeServer(SpiderSenseServer::daemonScheduler);
        }
        return server;
    }

    /**
     * Jetty's own scheduler is the last non-daemon thread in the JVM.
     *
     * <p>{@code Server} creates a {@link ScheduledExecutorScheduler} in its
     * constructor and that scheduler's thread is not a daemon, so a command-line
     * application with Spider Sense attached would hang after its {@code main}
     * returned. The bean is swapped for a daemon one before the server starts;
     * the connector keeps a reference to the original, which is now unmanaged and
     * never started, and whose {@code schedule} is a no-op — the only thing lost
     * is the connection idle timeout, which a loopback UI does not need.
     */
    private static void daemonScheduler(org.eclipse.jetty.server.Server jetty) {
        Scheduler current = jetty.getBean(Scheduler.class);
        if (current != null) {
            jetty.removeBean(current);
        }
        jetty.addBean(new ScheduledExecutorScheduler("spider-sense-scheduler", true), true);
    }

    /**
     * The UI's router is hash-based, so anything that is not an API call, not an
     * OTLP endpoint and not a file is the single page.
     */
    private static WebResponse notFound(net.benelog.spidersilk.WebRequest req) {
        String path = req.path();
        boolean page = "GET".equals(req.method())
                && !path.startsWith("/api/")
                && !path.startsWith("/v1/")
                && !hasExtension(path);
        if (page) {
            String index = index();
            if (index != null) {
                // The error handler keeps the status it was called with unless told otherwise.
                return WebResponse.html(index).status(HttpStatus.OK);
            }
        }
        // A handler that threw a 404 said why ("No such trace: …"); keep its words. The
        // framework's own "Not Found: /path" for an unmatched route is not worth keeping.
        String message = req.errorMessage();
        boolean generic = message == null || message.isBlank() || message.startsWith("Not Found");
        return ErrorBody.response(HttpStatus.NOT_FOUND, generic ? null : message, "Not found: " + path);
    }

    private static boolean hasExtension(String path) {
        int slash = path.lastIndexOf('/');
        return path.indexOf('.', slash + 1) >= 0;
    }

    /**
     * Read through this class's own class loader, never the thread context one: in
     * agent mode the context loader is the monitored application's, which knows
     * nothing about our resources. Spider Silk's {@code StaticFiles} resolves the
     * same way ({@code StaticFiles.class.getResource}), so the two agree.
     */
    private static @Nullable String index() {
        try (InputStream in = SpiderSenseServer.class.getClassLoader().getResourceAsStream(INDEX)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return app.port();
    }

    public Config config() {
        return config;
    }

    /** For tests and for anything that wants to look inside without an HTTP call. */
    public Store store() {
        return store;
    }

    public App app() {
        return app;
    }

    public void join() {
        app.join();
    }

    public void stop() {
        app.stop();
        store.close();
    }

    @Override
    public void close() {
        stop();
    }
}
