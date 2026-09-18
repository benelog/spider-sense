package warehouse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application on a real connector, driven over HTTP, because everything
 * worth checking here is container behaviour: the servlet mappings, the error
 * pages, the filter, and the async timeout.
 */
class WarehouseAppTest {

    private static WarehouseApp app;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void startApp() throws Exception {
        app = new WarehouseApp("jdbc:h2:mem:warehouse-test;DB_CLOSE_DELAY=-1", 0, 300);
        app.start();
        base = "http://127.0.0.1:" + app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void indexListsTheCounts() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("servlet-warehouse", "300 items");
        assertThat(response.headers().firstValue("X-Warehouse")).contains("1");
    }

    @Test
    void itemListIsATable() throws Exception {
        HttpResponse<String> response = get("/items");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<table>", "SKU-000001");
    }

    @Test
    void searchRunsTheFullScan() throws Exception {
        HttpResponse<String> response = get("/items?q=hinge");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hinge");
    }

    @Test
    void itemPageShowsItsMovements() throws Exception {
        HttpResponse<String> response = get("/items/SKU-000001");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("SKU-000001", "Movements (40)");
    }

    @Test
    void unknownSkuIsNotFound() throws Exception {
        HttpResponse<String> response = get("/items/NOPE");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404", "NOPE");
    }

    @Test
    void stockIsJson() throws Exception {
        HttpResponse<String> response = get("/api/stock/SKU-000002");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"sku\":\"SKU-000002\"", "\"quantity\":", "\"location\":");
    }

    @Test
    void aMovementChangesTheQuantity() throws Exception {
        int before = quantityOf("SKU-000003");

        HttpResponse<String> created = post("/api/movements", "sku=SKU-000003&delta=7&note=goods+in");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"sku\":\"SKU-000003\"");
        assertThat(quantityOf("SKU-000003")).isEqualTo(before + 7);
    }

    @Test
    void aMovementThatWouldGoNegativeIsAConflict() throws Exception {
        HttpResponse<String> response = post("/api/movements", "sku=SKU-000004&delta=-100000&note=oops");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("would go negative");
    }

    @Test
    void aDeltaThatIsNotANumberIsABadRequest() throws Exception {
        HttpResponse<String> response = post("/api/movements", "sku=SKU-000004&delta=lots");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void reportAggregatesEveryItem() throws Exception {
        HttpResponse<String> response = get("/api/report");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"categories\":[", "\"topSuppliers\":[");
    }

    @Test
    void asyncCompletesOnAnotherThread() throws Exception {
        HttpResponse<String> response = get("/api/async?ms=100");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"waitedMs\":100}");
    }

    @Test
    void asyncThatOverrunsTheTimeoutIsUnavailable() throws Exception {
        HttpResponse<String> response = get("/api/async?ms=3000");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("timed out");
    }

    @Test
    void healthIsOk() throws Exception {
        HttpResponse<String> response = get("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void flakyIsEitherFineOrAFiveHundred() throws Exception {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            seen.add(get("/api/flaky").statusCode());
        }

        assertThat(seen).isSubsetOf(Set.of(200, 500));
    }

    private int quantityOf(String sku) throws Exception {
        String body = get("/api/stock/" + sku).body();
        String marker = "\"quantity\":";
        int from = body.indexOf(marker) + marker.length();
        int to = body.indexOf(',', from);
        return Integer.parseInt(body.substring(from, to));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
