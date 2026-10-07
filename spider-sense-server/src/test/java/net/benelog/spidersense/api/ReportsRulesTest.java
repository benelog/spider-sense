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
                    .hasMessage("before=" + NOW + " (" + Text.instant(NOW) + ") is not before after=" + NOW
                            + " (" + Text.instant(NOW) + "), so the before window would be empty;"
                            + " name an earlier before or a later after (`marks` lists the marks)");
            assertThatThrownBy(() -> reports.compare(NOW - 10, NOW + 10, NOW, null, false))
                    .isInstanceOf(Selectors.BadSelector.class)
                    .hasMessage("after=" + (NOW + 10) + " (" + Text.instant(NOW + 10) + ") is not before until="
                            + NOW + " (" + Text.instant(NOW) + "), so the after window would be empty;"
                            + " name an earlier after or a later until");
            assertThat(reports.compare(NOW - 10, NOW, NOW + 10, null, false).json().asObject().has("before"))
                    .as("adjacent windows are answered").isTrue();
        }
    }

    /**
     * The refusal an agent met in onboarding: a mark taken after the application started,
     * compared against that start. It names both selectors, their local times and the start's
     * service, and what to do, rather than two epoch milliseconds.
     */
    @Test
    void aCompareRefusalNamesTheSelectorsTheirTimesAndWhatToDo() {
        Config config = TestStore.config();
        try (Database database = Database.open(config.jdbcUrl(), null)) {
            Marks marks = new Marks(database.sql(), () -> NOW);
            Reports.Parts parts = Reports.Parts.of(config, database, new Tingles(500, 100),
                    new ServiceRegistry(database.sql(), null), marks, () -> NOW);
            Reports reports = new Reports(config, database, null, () -> 0, parts, false, () -> NOW);
            TestStore.startMark(database.sql(), "silk-bookstore", "pid 1", NOW - 600_000);
            marks.create("onboarding-before", null, null, NOW - 60_000);

            assertThatThrownBy(() -> reports.compare("onboarding-before", "start", null, null, false))
                    .isInstanceOf(Selectors.BadSelector.class)
                    .hasMessage("before=onboarding-before (" + Text.instant(NOW - 60_000)
                            + ") is not before after=start (the start of silk-bookstore, "
                            + Text.instant(NOW - 600_000) + "), so the before window would be empty;"
                            + " restart the application so a new start mark is written, or record"
                            + " `mark after` once the change is in and use after=after");
            assertThatThrownBy(() -> reports.compare("5m", "now", null, null, false))
                    .isInstanceOf(Selectors.BadSelector.class)
                    .hasMessage("after=now (" + Text.instant(NOW) + ") is not before until=now ("
                            + Text.instant(NOW) + "), so the after window would be empty; after=now leaves"
                            + " no time after it: name the moment the change went in, such as a mark"
                            + " recorded after it");
        }
    }
}
