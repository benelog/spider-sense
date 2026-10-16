package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.query.Check;
import net.benelog.spidersense.query.MetricQueries;
import net.benelog.spidersense.store.MetricPoint;
import net.benelog.silkjson.JsonArray;
import net.benelog.silkjson.JsonObject;

/**
 * Numbers the API writes: its own durations rounded to three decimals where a third decimal
 * exists, never clamped, and a metric's or an attribute's numbers as they are.
 */
class CodecsTest {

    @Test
    void aDurationIsRoundedToThreeDecimals() {
        assertThat(Codecs.round(12.34567)).isEqualTo(12.346);
    }

    /** A check's limit and actual value are unrounded, so a rule failing by a hair never reads as its limit. */
    @Test
    void aChecksLimitAndActualValueAreNotRounded() {
        JsonArray checks = Codecs.checkResult(new Check.CheckResult(false, 2_500, null, List.of(
                new Check.RuleCheck(Check.MAX_ERROR_RATE, 0.0005, 0.0004, true, "r"),
                new Check.RuleCheck(Check.MIN_APDEX, 0.9, null, true, "a"))))
                .getArray("checks");

        assertThat(checks.get(0).asObject().getDouble("limit")).isEqualTo(0.0005);
        assertThat(checks.get(0).asObject().getDouble("actual")).isEqualTo(0.0004);
        assertThat(checks.get(1).asObject().get("actual").isNull()).as("no request to score").isTrue();
    }

    @Test
    void aValueTooLargeForThreeDecimalsIsWrittenAsItIs() {
        assertThat(Codecs.round(1e20)).isEqualTo(1e20);
        assertThat(Codecs.round(1.2345678901234568e16)).isEqualTo(1.2345678901234568e16);
        assertThat(Codecs.round(-1e20)).isEqualTo(-1e20);
        assertThat(Codecs.round(Double.MAX_VALUE)).isEqualTo(Double.MAX_VALUE);
    }

    /**
     * A metric is in its own unit, and OpenTelemetry's durations are seconds: three decimals of a
     * second would read a 0.4 ms mean as 0 and every value as whole milliseconds (api.adoc#metrics).
     */
    @Test
    void aMetricsValuesAreWrittenUnrounded() {
        MetricPoint point = MetricPoint.histogram(1_000L, 3, 0.0012, 0.0002, 0.0007,
                new long[] {1, 1, 1}, new double[] {0.0003, 0.0006});
        JsonObject series = Codecs.series(new MetricQueries.SeriesData("hist", "http.server.request.duration",
                "histogram", "s", false, "CUMULATIVE", Map.of(), List.of(point)), false);

        assertThat(series.getArray("v").get(0).asDouble()).isEqualTo(0.0012 / 3);
        assertThat(series.getArray("p95").get(0).asDouble()).isEqualTo(point.percentile(0.95));
        assertThat(series.getArray("max").get(0).asDouble()).isEqualTo(0.0007);
    }

    /** An attribute is the application's number, written as the store holds it (api.adoc#span). */
    @Test
    void anAttributesDoubleIsWrittenUnrounded() {
        JsonObject attributes = Codecs.attributes(Map.of("app.sample_rate", 0.0004, "app.ratio", 0.12345,
                "app.list", List.of(0.0004, 1.5)));

        assertThat(attributes.get("app.sample_rate").asDouble()).isEqualTo(0.0004);
        assertThat(attributes.get("app.ratio").asDouble()).isEqualTo(0.12345);
        assertThat(attributes.getArray("app.list").get(0).asDouble()).isEqualTo(0.0004);
    }
}
