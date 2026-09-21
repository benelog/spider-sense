package orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole application against an in-memory H2 with a tiny seed.
 * The HTTP client is the JDK's, so nothing here depends on a test-only web client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:orders-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "orders.seed.customers=10",
        "orders.seed.products=10",
        "orders.seed.orders=50",
        "orders.bookstore.base-url=http://localhost:1"
})
class OrdersApiTest {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern CUSTOMER_ID = Pattern.compile("\"customerId\"\\s*:\\s*(\\d+)");
    private static final Pattern PRODUCT_ID = Pattern.compile("\"productId\"\\s*:\\s*(\\d+)");

    private static HttpClient http;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createClient() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void createPayAndShipAnOrder() throws Exception {
        long customerId = firstCustomerId();
        long productId = firstProductId();

        HttpResponse<String> created = post("/api/orders",
                "{\"customerId\":" + customerId + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":2}]}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"status\":\"NEW\"");
        long orderId = firstMatch(ID, created.body());

        HttpResponse<String> paid = post("/api/orders/" + orderId + "/pay", "");
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(paid.body()).contains("\"status\":\"PAID\"");

        HttpResponse<String> shipped = post("/api/orders/" + orderId + "/ship", "");
        assertThat(shipped.statusCode()).isEqualTo(200);
        assertThat(shipped.body()).contains("\"status\":\"SHIPPED\"");
    }

    @Test
    void payingTwiceIsAConflict() throws Exception {
        long customerId = firstCustomerId();
        long productId = firstProductId();

        HttpResponse<String> created = post("/api/orders",
                "{\"customerId\":" + customerId + ",\"lines\":[{\"productId\":" + productId + ",\"quantity\":1}]}");
        long orderId = firstMatch(ID, created.body());

        assertThat(post("/api/orders/" + orderId + "/pay", "").statusCode()).isEqualTo(200);

        HttpResponse<String> again = post("/api/orders/" + orderId + "/pay", "");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("is not payable from PAID");
    }

    @Test
    void creatingWithNoLinesIsABadRequest() throws Exception {
        HttpResponse<String> response = post("/api/orders", "{\"customerId\":1,\"lines\":[]}");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void singleOrderHasLinesAndProductNames() throws Exception {
        long orderId = firstOrderId();
        HttpResponse<String> response = get("/api/orders/" + orderId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":" + orderId)
                .contains("\"customerName\"")
                .contains("\"status\"")
                .contains("\"total\"")
                .contains("\"lines\"")
                .contains("\"productName\"")
                .contains("\"unitPrice\"");
    }

    @Test
    void missingOrderIsNotFound() throws Exception {
        assertThat(get("/api/orders/999999").statusCode()).isEqualTo(404);
    }

    @Test
    void enrichedOrderSurvivesTheBookstoreBeingDown() throws Exception {
        HttpResponse<String> response = get("/api/orders/" + firstOrderId() + "/enriched");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"book\":null");
    }

    @Test
    void theBatchEnrichmentAnswersTheSameShape() throws Exception {
        HttpResponse<String> response = get("/api/orders/" + firstOrderId() + "/enriched-batch");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("one call for every line, and still 200 when the bookstore is down")
                .contains("\"book\":null");
    }

    @Test
    void revenueReportAnswers() throws Exception {
        HttpResponse<String> response = get("/api/reports/revenue?days=365");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("byStatusAndDay").contains("topProducts");
    }

    @Test
    void customerSearchAnswers() throws Exception {
        HttpResponse<String> response = get("/api/customers/search?q=a");
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void flakyIsEitherOkOrAFailure() throws Exception {
        boolean sawSomething = false;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = get("/api/flaky");
            assertThat(response.statusCode()).isIn(200, 500);
            sawSomething = true;
        }
        assertThat(sawSomething).isTrue();
    }

    @Test
    void slowSleepsForTheRequestedTime() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = get("/api/slow?ms=200");
        long elapsed = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).isGreaterThanOrEqualTo(190);
    }

    @Test
    void healthAndIndexAnswer() throws Exception {
        assertThat(get("/api/health").body()).contains("\"status\":\"ok\"");
        assertThat(get("/").body()).contains("spring-orders");
    }

    private long firstOrderId() throws Exception {
        return firstMatch(ID, get("/api/orders?page=0&size=1").body());
    }

    private long firstCustomerId() throws Exception {
        return firstMatch(CUSTOMER_ID, get("/api/orders?page=0&size=1").body());
    }

    private long firstProductId() throws Exception {
        return firstMatch(PRODUCT_ID, get("/api/orders?page=0&size=1").body());
    }

    private static long firstMatch(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        assertThat(matcher.find()).as("no match for %s in %s", pattern, body).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
