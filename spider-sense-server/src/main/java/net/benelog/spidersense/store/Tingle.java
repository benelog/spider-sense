package net.benelog.spidersense.store;

/**
 * A noteworthy event, derived at ingest against the configured thresholds.
 *
 * <p>The name is the product's: the spider senses something. The Overview lists
 * the recent ones and the SSE stream pushes each as it happens.
 *
 * @param kind one of {@code slow-request}, {@code slow-query}, {@code error}
 */
public record Tingle(String kind, long at, String service, String title, String detail,
        String traceId, String spanId, double durationMs) {

    public static final String SLOW_REQUEST = "slow-request";
    public static final String SLOW_QUERY = "slow-query";
    public static final String ERROR = "error";
}
