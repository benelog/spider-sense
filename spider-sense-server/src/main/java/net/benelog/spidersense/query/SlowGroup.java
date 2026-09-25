package net.benelog.spidersense.query;

import java.util.Map;

/**
 * One group a {@code slow-*} finding is about, with the percentiles every such finding
 * carries: the requests of an endpoint, the runs of a job, the calls of a statement
 * or of an outbound call (findings.adoc#slow-endpoint).
 *
 * @param name  the endpoint, the job, or the call's span name
 * @param count its requests, runs or calls over the window
 */
record SlowGroup(String service, String name, long count, double p50Ms, double p95Ms,
        double maxMs, double totalMs) {

    static SlowGroup of(Stats.EndpointStats endpoint) {
        return new SlowGroup(endpoint.service(), endpoint.name(), endpoint.calls(), endpoint.p50Ms(),
                endpoint.p95Ms(), endpoint.maxMs(), endpoint.totalMs());
    }

    /**
     * {@code p50Ms}, {@code p95Ms}, {@code maxMs} and {@code totalMs}, in that order.
     *
     * <p>The count is the caller's to put first, because its key differs by kind
     * ({@code calls}, {@code runs}) and {@code slow-query} and {@code slow-external}
     * put one more number between it and these.
     */
    void putPercentiles(Map<String, Object> numbers) {
        numbers.put("p50Ms", p50Ms);
        numbers.put("p95Ms", p95Ms);
        numbers.put("maxMs", maxMs);
        numbers.put("totalMs", totalMs);
    }
}
