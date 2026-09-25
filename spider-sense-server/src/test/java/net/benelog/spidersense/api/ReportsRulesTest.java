package net.benelog.spidersense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;
import net.benelog.spidersense.query.Findings;
import net.benelog.spidersense.query.Selectors;
import net.benelog.spidersense.query.Window;
import net.benelog.spidersense.server.Config;
import net.benelog.spidersense.store.Database;
import net.benelog.spidersense.store.Marks;
import net.benelog.spidersense.store.ServiceRegistry;
import net.benelog.spidersense.store.Tingles;
import net.benelog.spidersilk.json.Json;

/**
 * What {@link Reports} decides itself rather than asks the queries: a finding's
 * rank, and the compare windows it refuses. Neither needs a span.
 */
class ReportsRulesTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Window WINDOW = Window.of(NOW - 60_000, NOW);

    private static Findings.Finding finding(String id) {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("count", 1L);
        return new Findings.Finding(id, Findings.ERROR, Findings.HIGH, "orders", "title " + id, "why " + id,
                Findings.Subject.error(id), numbers, null, List.of(), List.of());
    }

    @Test
    void aFindingIsNumberedByItsPlaceAmongEveryFindingInBothRenderings() {
        List<Findings.Finding> ranked = List.of(finding("a"), finding("b"), finding("c"));

        Reports.Report report = Reports.finding(WINDOW, null, ranked, "b", false, () -> 42);

        assertThat(report).isNotNull();
        Json.JsonObject json = report.json().asObject();
        assertThat(json.getLong("rank")).isEqualTo(2);
        assertThat(json.getLong("requests")).isEqualTo(42);
        assertThat(json.getObject("finding").getString("id")).isEqualTo("b");
        assertThat(report.text()).contains("\n2. b — why b\n");
    }

    @Test
    void aFindingTheRulesDidNotProduceIsNullAndCountsNothing() {
        AtomicInteger asked = new AtomicInteger();

        assertThat(Reports.finding(WINDOW, null, List.of(finding("a")), "z", false,
                () -> asked.incrementAndGet())).isNull();
        assertThat(asked).as("the request count is read only for a finding that is there").hasValue(0);
    }

    @Test
    void aCompareWhoseWindowsWouldBeEmptyOrInvertedIsRefusedNamingTheMoments() {
        Config config = TestStore.config();
        try (Database database = Database.open(config.jdbcUrl(), null)) {
            Marks marks = new Marks(database.sql(), () -> NOW);
            Reports.Parts parts = Reports.Parts.of(config, database, new Tingles(500, 100),
                    new ServiceRegistry(database.sql(), null), marks, () -> NOW);
            Reports reports = new Reports(config, database, null, () -> 0, parts, false, () -> NOW);

            assertThatThrownBy(() -> reports.compare(NOW, NOW, NOW + 10, null, false))
                    .isInstanceOf(Selectors.BadSelector.class)
                    .hasMessage("before resolves to " + NOW + ", which is not before after " + NOW);
            assertThatThrownBy(() -> reports.compare(NOW - 10, NOW + 10, NOW, null, false))
                    .isInstanceOf(Selectors.BadSelector.class)
                    .hasMessage("after resolves to " + (NOW + 10) + ", which is not before until " + NOW);
            assertThat(reports.compare(NOW - 10, NOW, NOW + 10, null, false).json().asObject().has("before"))
                    .as("adjacent windows are answered").isTrue();
        }
    }
}
