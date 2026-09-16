package orders.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls silk-bookstore over HTTP with Spring's RestClient.
 * The OpenTelemetry agent instruments RestClient and injects the trace context into the request,
 * so one trace covers spring-orders and silk-bookstore.
 * The bookstore is optional: when it is down or slow the caller gets null and the order is still returned.
 */
@Component
public class BookstoreClient {

    private static final Logger log = LoggerFactory.getLogger(BookstoreClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public BookstoreClient(RestClient.Builder builder,
                           @Value("${orders.bookstore.base-url:http://localhost:8081}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Map<String, Object> findBook(long bookId) {
        try {
            return restClient.get()
                    .uri("/api/books/{id}", bookId)
                    .retrieve()
                    .body(MAP);
        } catch (Exception e) {
            log.debug("bookstore lookup for book {} failed: {}", bookId, e.toString());
            return null;
        }
    }
}
