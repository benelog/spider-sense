package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The glob matching behind {@code spidersense.ignore.endpoints} (configuration.adoc#ignored-endpoints). */
class IgnoredEndpointsTest {

    private final IgnoredEndpoints defaults = IgnoredEndpoints.defaults();

    /** A server span with a route, which is what an endpoint name is usually made of. */
    private static SpanRecord route(String method, String route) {
        return span("SERVER", method + " " + route,
                Map.of("http.request.method", method, "http.route", route));
    }

    private static SpanRecord span(String kind, String name, Map<String, Object> attributes) {
        return new SpanRecord("a".repeat(32), "b".repeat(16), null, "orders", name, kind,
                1_000_000_000L, 1_010_000_000L, "UNSET", null, attributes, List.of(), "scope");
    }

    @Test
    void theDefaultListIgnoresTheUsualHealthChecksWhateverTheMethod() {
        assertThat(defaults.patterns())
                .containsExactly("/actuator/**", "/health", "/healthz", "/livez", "/readyz");

        assertThat(defaults.matches(route("GET", "/actuator/health"))).isTrue();
        assertThat(defaults.matches(route("GET", "/actuator/health/liveness"))).isTrue();
        assertThat(defaults.matches(route("POST", "/health"))).isTrue();
        assertThat(defaults.matches(route("GET", "/readyz"))).isTrue();
    }

    @Test
    void theDefaultListLeavesAnEndpointThatMerelyLooksLikeOneAlone() {
        assertThat(defaults.matches(route("GET", "/healthy"))).isFalse();
        assertThat(defaults.matches(route("GET", "/orders/health"))).isFalse();
        assertThat(defaults.matches(route("GET", "/actuator"))).isFalse();
    }

    @Test
    void aPatternWithAMethodCoversThatMethodAlone() {
        IgnoredEndpoints ignored = IgnoredEndpoints.of("GET /actuator/**");

        assertThat(ignored.matches(route("GET", "/actuator/health"))).isTrue();
        assertThat(ignored.matches(route("POST", "/actuator/health"))).isFalse();
    }

    @Test
    void oneStarDoesNotCrossASlashAndTwoStarsDo() {
        IgnoredEndpoints one = IgnoredEndpoints.of("/admin/*");
        assertThat(one.matches(route("GET", "/admin/status"))).isTrue();
        assertThat(one.matches(route("GET", "/admin/status/db"))).isFalse();

        IgnoredEndpoints two = IgnoredEndpoints.of("/admin/**");
        assertThat(two.matches(route("GET", "/admin/status/db"))).isTrue();
    }

    @Test
    void aQuestionMarkIsExactlyOneCharacter() {
        IgnoredEndpoints ignored = IgnoredEndpoints.of("/v?/health");

        assertThat(ignored.matches(route("GET", "/v1/health"))).isTrue();
        assertThat(ignored.matches(route("GET", "/v12/health"))).isFalse();
        assertThat(ignored.matches(route("GET", "//health"))).isFalse();
    }

    @Test
    void aSpanWithNoRouteIsMatchedOnItsUrlPath() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("http.request.method", "GET");
        attributes.put("url.path", "/actuator/health");
        SpanRecord span = span("SERVER", "GET", attributes);

        assertThat(span.endpointName()).isEqualTo("GET");
        assertThat(defaults.matches(span)).isTrue();
    }

    @Test
    void anEmptyValueIgnoresNothing() {
        IgnoredEndpoints ignored = IgnoredEndpoints.of("");

        assertThat(ignored.isEmpty()).isTrue();
        assertThat(ignored.patterns()).isEmpty();
        assertThat(ignored.matches(route("GET", "/actuator/health"))).isFalse();
    }

    @Test
    void blanksAreTrimmedAwayAndEmptyEntriesDropped() {
        IgnoredEndpoints ignored = IgnoredEndpoints.of(" /health , , /livez ");

        assertThat(ignored.patterns()).containsExactly("/health", "/livez");
        assertThat(ignored.matches(route("GET", "/livez"))).isTrue();
    }

    @Test
    void theMatchIsCaseSensitive() {
        assertThat(defaults.matches(route("GET", "/Health"))).isFalse();
        assertThat(IgnoredEndpoints.of("/HEALTH").matches(route("GET", "/health"))).isFalse();
    }
}
