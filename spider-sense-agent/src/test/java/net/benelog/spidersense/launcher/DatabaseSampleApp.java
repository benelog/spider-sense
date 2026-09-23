package net.benelog.spidersense.launcher;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The application DatabaseCatalogIT monitors: SampleApp's slow statement, run against the
 * PostgreSQL or MySQL database named by {@code -Dsample.jdbc.url} rather than an in-memory H2.
 *
 * <p>The statement runs inside an HTTP request, on a connection opened before it, so the trace it
 * belongs to is the request's: a catalog query the extension let through would show up as a second
 * database span of that trace (design.md, "The extension").
 *
 * <p>The table is created as {@code Items}, unquoted, and the statement names it that way too.
 * PostgreSQL stores it as {@code items}, MySQL keeps {@code Items}, and the catalog lookup finds it
 * only by folding the name the way each database says it does.
 *
 * <p>It is compiled by the test source set but never run by JUnit: DatabaseCatalogIT spawns it in
 * a JVM of its own with {@code -javaagent}.
 */
public final class DatabaseSampleApp {

    private DatabaseSampleApp() {
    }

    /** A fresh table with one indexed and one unindexed predicate column, as SampleApp has. */
    static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists Items");
            statement.execute("create table Items (id bigint primary key, name varchar(100),"
                    + " supplier_id bigint)");
            statement.execute("create index Idx_Items_Supplier on Items (supplier_id, name)");
            statement.execute("insert into Items values (1, 'hinge', 7)");
        }
    }

    /**
     * The slow statement: the database's own sleep, which both take in seconds, and a filter on
     * {@code supplier_id}, which leads an index, and on {@code name}, which no index leads with.
     */
    static void slowQuery(Connection connection, long millis, boolean postgres) throws SQLException {
        String sleep = postgres ? "pg_sleep(?)" : "sleep(?)";
        try (PreparedStatement statement = connection.prepareStatement(
                "select " + sleep + ", count(*) from Items where name = ? and supplier_id = ?")) {
            statement.setDouble(1, millis / 1000.0);
            statement.setString(2, "hinge");
            statement.setLong(3, 7);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    // Drain it; the time it took is the point.
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("sample.jdbc.url");
        if (url == null) {
            throw new IllegalArgumentException("-Dsample.jdbc.url is required");
        }
        boolean postgres = url.startsWith("jdbc:postgresql:");
        long millis = Long.getLong("sample.db.sleep.ms", 400L);

        try (Connection connection = DriverManager.getConnection(url,
                System.getProperty("sample.jdbc.user"), System.getProperty("sample.jdbc.password"))) {
            createSchema(connection);

            // The address is a literal and not a host name, so there is nothing to select between.
            @SuppressWarnings("AddressSelection")
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService pool = Executors.newFixedThreadPool(1, runnable -> {
                Thread t = new Thread(runnable, "sample-http");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.createContext("/slow", exchange -> {
                int status = 200;
                try {
                    slowQuery(connection, millis, postgres);
                } catch (SQLException e) {
                    System.out.println("sample: " + e);
                    status = 500;
                }
                byte[] body = "done".getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();

            URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(target).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                System.out.println("sample: GET " + target + " -> " + response.statusCode());
            } finally {
                server.stop(0);
                pool.shutdownNow();
            }
        }
        Thread.sleep(Long.getLong("sample.linger.ms", 4000L));
        System.out.println("sample: done");
    }
}
