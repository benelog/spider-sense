package orders.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
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

    private static final ParameterizedTypeReference<List<Map<String, Object>>> BOOK_LIST =
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

    public @Nullable Map<String, Object> findBook(long bookId) {
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

    /**
     * The whole set in one call, which is what the N+1 above should have been.
     * The answer is keyed by id, so a caller that looped can index into it; an id the
     * bookstore does not know is simply absent, and a failed call is an empty map, as
     * a failed single lookup is null.
     */
    public Map<Long, Map<String, Object>> findBooks(List<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(bookIds.size());
        for (Long bookId : new LinkedHashSet<>(bookIds)) {
            ids.add(String.valueOf(bookId));
        }
        try {
            List<Map<String, Object>> books = restClient.get()
                    .uri(builder -> builder.path("/api/books")
                            .queryParam("ids", String.join(",", ids)).build())
                    .retrieve()
                    .body(BOOK_LIST);
            if (books == null) {
                return Map.of();
            }
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> book : books) {
                if (book.get("id") instanceof Number id) {
                    byId.put(id.longValue(), book);
                }
            }
            return byId;
        } catch (Exception e) {
            log.debug("bookstore lookup for {} books failed: {}", ids.size(), e.toString());
            return Map.of();
        }
    }
}
