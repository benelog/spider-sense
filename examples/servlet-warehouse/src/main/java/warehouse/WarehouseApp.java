package warehouse;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Servlet;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import warehouse.web.AsyncServlet;
import warehouse.web.ErrorServlet;
import warehouse.web.FlakyServlet;
import warehouse.web.HealthServlet;
import warehouse.web.IndexServlet;
import warehouse.web.ItemListServlet;
import warehouse.web.ItemServlet;
import warehouse.web.MovementServlet;
import warehouse.web.ReportServlet;
import warehouse.web.RequestLogFilter;
import warehouse.web.StockServlet;

/**
 * A warehouse on a plain Servlet stack: embedded Tomcat 11, servlets registered
 * by hand, plain JDBC over Tomcat's own connection pool, an H2 file database.
 * No framework sits between the servlet and the OpenTelemetry agent, which is
 * the point: it shows what Spider Sense makes of a Servlet application on its
 * own, where an endpoint is named after the servlet mapping.
 *
 * <p>Like the other examples it misbehaves deliberately: a full scan over
 * 100,000 rows, an N+1 detail page, a slow aggregate report, an async servlet
 * that finishes on another thread and sometimes times out, and one that throws
 * where nobody catches it.
 */
public class WarehouseApp {

    public static final int PORT = 8083;

    /** The database file, shared with anything else that opens it thanks to AUTO_SERVER. */
    public static final String JDBC_URL = "jdbc:h2:~/db/spider-sense/warehouse;AUTO_SERVER=TRUE";

    private final String jdbcUrl;
    private final int requestedPort;
    private final int seedItems;

    private Tomcat tomcat;
    private DataSource dataSource;
    private AsyncServlet asyncServlet;

    public WarehouseApp(String jdbcUrl, int requestedPort, int seedItems) {
        this.jdbcUrl = jdbcUrl;
        this.requestedPort = requestedPort;
        this.seedItems = seedItems;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("warehouse.port", PORT);
        String url = System.getProperty("warehouse.db", JDBC_URL);
        int items = Integer.getInteger("warehouse.seed.items", Seeder.DEFAULT_ITEMS);

        WarehouseApp app = new WarehouseApp(url, port, items);
        app.start();
        System.out.println("servlet-warehouse: http://localhost:" + app.port());
        app.await();
    }

    /** Opens the pool, seeds if needed, registers everything, starts the connector. */
    public int start() throws Exception {
        dataSource = pool(jdbcUrl);
        new Seeder(dataSource, seedItems).seed();

        // Embedded Tomcat is talkative on INFO and has nothing to say that a
        // reader of this example wants; the one line worth printing is ours.
        Logger.getLogger("org.apache").setLevel(Level.WARNING);

        File base = Files.createTempDirectory("servlet-warehouse").toFile();
        base.deleteOnExit();

        tomcat = new Tomcat();
        tomcat.setBaseDir(base.getAbsolutePath());
        tomcat.setPort(requestedPort);
        tomcat.getConnector();

        Context ctx = tomcat.addContext("", base.getAbsolutePath());
        registerFilter(ctx);
        registerServlets(ctx);
        registerErrorPages(ctx);

        tomcat.start();
        return port();
    }

    /**
     * Every servlet and its mapping, which is also every endpoint the APM will
     * name: the OpenTelemetry agent takes {@code http.route} from the servlet
     * mapping, so {@code /items/*} is one endpoint however many SKUs go through it.
     */
    private void registerServlets(Context ctx) {
        add(ctx, "index", new IndexServlet(dataSource), "", false);
        add(ctx, "itemList", new ItemListServlet(dataSource), "/items", false);
        add(ctx, "item", new ItemServlet(dataSource), "/items/*", false);
        add(ctx, "stock", new StockServlet(dataSource), "/api/stock/*", false);
        add(ctx, "movement", new MovementServlet(dataSource), "/api/movements", false);
        add(ctx, "report", new ReportServlet(dataSource), "/api/report", false);
        asyncServlet = new AsyncServlet();
        add(ctx, "async", asyncServlet, "/api/async", true);
        add(ctx, "flaky", new FlakyServlet(), "/api/flaky", false);
        add(ctx, "health", new HealthServlet(), "/api/health", false);
        add(ctx, "error", new ErrorServlet(), "/error", false);
    }

    private void add(Context ctx, String name, Servlet servlet, String mapping, boolean async) {
        Wrapper wrapper = Tomcat.addServlet(ctx, name, servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(async);
        ctx.addServletMapping(mapping, name);
    }

    /**
     * One filter in front of everything. It has to declare async support too,
     * or the container refuses {@code startAsync()} for a request that came
     * through it.
     */
    private void registerFilter(Context ctx) {
        FilterDef def = new FilterDef();
        def.setFilterName("requestLog");
        def.setFilter(new RequestLogFilter());
        def.setAsyncSupported("true");
        ctx.addFilterDef(def);

        FilterMap map = new FilterMap();
        map.setFilterName("requestLog");
        map.addURLPattern("/*");
        map.setDispatcher(DispatcherType.REQUEST.name());
        ctx.addFilterMap(map);
    }

    /**
     * Tomcat's own error pages, which is how a 400, a 404, a 409 and a 500 become
     * JSON instead of the container's HTML report. A status with no page here —
     * there are none left that this application sends — would come back as
     * Tomcat's own page.
     */
    private void registerErrorPages(Context ctx) {
        for (int status : new int[]{400, 404, 409, 500}) {
            ErrorPage page = new ErrorPage();
            page.setErrorCode(status);
            page.setLocation("/error");
            ctx.addErrorPage(page);
        }
    }

    public int port() {
        return tomcat.getConnector().getLocalPort();
    }

    public void await() {
        tomcat.getServer().await();
    }

    public void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        if (asyncServlet != null) {
            asyncServlet.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * H2 keeps the result of a repeated identical query per session and hands it
     * back unchanged until the table is written to, which would make the second
     * call to {@code /api/report} cost nothing and the endpoint look fast. This
     * example exists to show what the query costs, so the cache is off and every
     * call does the work. H2 2.x takes it as a URL setting only; there is no
     * {@code SET QUERY_CACHE_SIZE} statement any more.
     */
    static String withoutQueryCache(String jdbcUrl) {
        return jdbcUrl.toUpperCase().contains("QUERY_CACHE_SIZE")
                ? jdbcUrl
                : jdbcUrl + ";QUERY_CACHE_SIZE=0";
    }

    /**
     * Tomcat's own JDBC pool rather than H2's, because the OpenTelemetry agent
     * instruments it: the connection counts show up on the JVM page beside the
     * heap, and an endpoint that holds a connection too long is visible.
     */
    static DataSource pool(String jdbcUrl) {
        PoolProperties properties = new PoolProperties();
        properties.setName("warehouse");
        properties.setUrl(withoutQueryCache(jdbcUrl));
        properties.setDriverClassName("org.h2.Driver");
        properties.setUsername("sa");
        properties.setPassword("");
        properties.setMaxActive(8);
        properties.setMaxWait(5_000);
        properties.setMaxIdle(8);
        properties.setMinIdle(2);
        properties.setInitialSize(2);
        properties.setTestOnBorrow(false);
        return new DataSource(properties);
    }
}
