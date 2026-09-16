package loadgen;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenariosTest {

    private static final String BOOKSTORE = "http://localhost:8081";
    private static final String ORDERS = "http://localhost:8082";

    @Test
    void weightsAddUpToTheAdvertisedTotal() {
        int sum = Scenarios.all().stream().mapToInt(Scenario::weight).sum();
        assertThat(sum).isEqualTo(Scenarios.TOTAL_WEIGHT).isEqualTo(109);
        assertThat(Scenarios.all()).allSatisfy(scenario -> assertThat(scenario.weight()).isPositive());
    }

    @Test
    void scenarioNamesAreUnique() {
        Set<String> names = new HashSet<>();
        for (Scenario scenario : Scenarios.all()) {
            assertThat(names.add(scenario.name())).as("duplicate scenario %s", scenario.name()).isTrue();
        }
    }

    @Test
    void everyScenarioBuildsValidUris() {
        Random random = new Random(7);
        for (Scenario scenario : Scenarios.all()) {
            for (int attempt = 0; attempt < 50; attempt++) {
                List<Step> steps = scenario.steps(random);
                assertThat(steps).as("%s produced no steps", scenario.name()).isNotEmpty();
                for (Step step : steps) {
                    Step resolved = step.withId(4242);
                    assertThat(resolved.path()).doesNotContain(Step.ID_PLACEHOLDER);
                    assertThat(resolved.method()).isIn("GET", "POST");

                    URI uri = resolved.uri(BOOKSTORE, ORDERS);
                    assertThat(uri.isAbsolute()).as("%s -> %s", scenario.name(), uri).isTrue();
                    assertThat(uri.getHost()).isEqualTo("localhost");
                    assertThat(uri.getPath()).startsWith("/");
                    assertThat(uri.getPort())
                            .isEqualTo(resolved.target() == Target.BOOKSTORE ? 8081 : 8082);
                    if (resolved.method().equals("POST")) {
                        assertThat(resolved.body()).isNotNull();
                    }
                }
            }
        }
    }

    @Test
    void checkoutChainsTheIdOfTheCreatedOrder() {
        Scenario checkout = Scenarios.all().stream()
                .filter(s -> s.name().equals("orders.checkout"))
                .findFirst()
                .orElseThrow();

        List<Step> steps = checkout.steps(new Random(1));
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).needsId()).isFalse();
        assertThat(steps.get(0).path()).isEqualTo("/api/orders");
        assertThat(steps.get(0).body()).contains("customerId").contains("productId");
        assertThat(steps.get(1).needsId()).isTrue();
        assertThat(steps.get(1).withId(9).path()).isEqualTo("/api/orders/9/pay");
        assertThat(steps.get(2).withId(9).path()).isEqualTo("/api/orders/9/ship");
    }

    @Test
    void theBookPageMostlyPicksABookThatHasReviews() {
        Scenario bookPage = Scenarios.all().stream()
                .filter(s -> s.name().equals("bookstore.book-page"))
                .findFirst()
                .orElseThrow();

        Random random = new Random(11);
        int reviewed = 0;
        for (int i = 0; i < 10_000; i++) {
            String path = bookPage.steps(random).get(0).path();
            int id = Integer.parseInt(path.substring("/books/".length()));
            assertThat(id).isBetween(1, Scenarios.BOOKS);
            if (id <= Scenarios.REVIEWED_BOOKS) {
                reviewed++;
            }
        }
        // 4 in 5 by construction; the rest of the catalogue has no reviews and so no N+1.
        assertThat(reviewed).isBetween(7500, 8500);
    }

    @Test
    void pickEventuallyReturnsEveryScenario() {
        Random random = new Random(99);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(Scenarios.pick(random).name());
        }
        assertThat(seen).hasSize(Scenarios.all().size());
    }

    @Test
    void pickRespectsTheWeights() {
        Random random = new Random(3);
        int bookPage = 0;
        int ordersSlow = 0;
        for (int i = 0; i < 100_000; i++) {
            String name = Scenarios.pick(random).name();
            if (name.equals("bookstore.book-page")) {
                bookPage++;
            } else if (name.equals("orders.slow")) {
                ordersSlow++;
            }
        }
        // weights 20 and 1: the heavy one must be far more frequent.
        assertThat(bookPage).isGreaterThan(ordersSlow * 10);
    }

    @Test
    void optionsHaveTheDocumentedDefaults() {
        Options options = Options.parse(new String[0]);
        assertThat(options.bookstore()).isEqualTo("http://localhost:8081");
        assertThat(options.orders()).isEqualTo("http://localhost:8082");
        assertThat(options.rps()).isEqualTo(4);
        assertThat(options.durationSeconds()).isZero();
        assertThat(options.concurrency()).isEqualTo(4);
        assertThat(options.intervalMillis()).isEqualTo(250);
    }

    @Test
    void optionsAreParsedAndTrailingSlashesDropped() {
        Options options = Options.parse(new String[]{
                "--bookstore=http://127.0.0.1:9/", "--orders=http://127.0.0.1:8082",
                "--rps=10", "--duration=20", "--seed=5", "--concurrency=2", "--wait=1"});
        assertThat(options.bookstore()).isEqualTo("http://127.0.0.1:9");
        assertThat(options.rps()).isEqualTo(10);
        assertThat(options.durationSeconds()).isEqualTo(20);
        assertThat(options.seed()).isEqualTo(5);
        assertThat(options.concurrency()).isEqualTo(2);
        assertThat(options.waitSeconds()).isEqualTo(1);
        assertThat(options.intervalMillis()).isEqualTo(100);
    }
}
