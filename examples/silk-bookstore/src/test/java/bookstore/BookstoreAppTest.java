package bookstore;

import java.util.List;

import javax.sql.DataSource;

import bookstore.service.Seeder;
import bookstore.web.RouteMatcher;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.test.WebTest;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application against an in-memory H2, seeded with a handful of books
 * instead of the 200,000 the file database gets. The slowness is the point of
 * the app but not of its tests, so what is asserted here is the behaviour:
 * which status, which shape of body.
 */
class BookstoreAppTest {

    private static final int SEEDED_BOOKS = 40;

    private static App app;

    @BeforeAll
    static void startApp() {
        DataSource dataSource = JdbcConnectionPool.create(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
        new Seeder(dataSource, SEEDED_BOOKS).seed();
        app = BookstoreApp.createApp(dataSource)
                .templates(BookstoreApp.templates(new String[0]));
    }

    @Test
    void homePageListsTheCounts() {
        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("silk-bookstore", String.valueOf(SEEDED_BOOKS));
        });
    }

    @Test
    void oneBookAsJson() {
        WebTest.test(app, client -> {
            var response = client.get("/api/books/1");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"id\":1")
                    .contains("\"isbn\"")
                    .contains("\"title\"")
                    .contains("\"author\"")
                    .contains("\"price\"")
                    .contains("\"publishedYear\"")
                    .contains("\"description\"");
        });
    }

    @Test
    void searchFindsMatches() {
        WebTest.test(app, client -> {
            var response = client.get("/api/books/search?q=dragon");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).containsIgnoringCase("dragon");
        });
    }

    @Test
    void theBookPageRunsItsNPlusOneAndRenders() {
        WebTest.test(app, client -> {
            var response = client.get("/books/1");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Reviews (20)");
        });
    }

    @Test
    void flakyAnswersEitherWay() {
        WebTest.test(app, client -> {
            for (int i = 0; i < 20; i++) {
                assertThat(client.get("/api/flaky").statusCode()).isIn(200, 500);
            }
        });
    }

    @Test
    void addingAReviewAnswers201() {
        WebTest.test(app, client -> {
            var response = client.postJson("/api/reviews",
                    "{\"bookId\":2,\"authorId\":3,\"rating\":5,\"body\":\"Fine\"}");
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).contains("\"rating\":5");
        });
    }

    @Test
    void aRatingOutsideOneToFiveAnswers400() {
        WebTest.test(app, client -> {
            var response = client.postJson("/api/reviews",
                    "{\"bookId\":2,\"authorId\":3,\"rating\":9,\"body\":\"Nope\"}");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("rating must be between 1 and 5");
        });
    }

    @Test
    void theMissingRouteIsAlways404() {
        WebTest.test(app, client -> {
            var response = client.get("/api/books/1/missing");
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("\"error\"");
        });
    }

    @Test
    void healthIsOk() {
        WebTest.test(app, client -> {
            assertThat(client.get("/api/health").body()).isEqualTo("{\"status\":\"ok\"}");
        });
    }

    @Test
    void theRouteMatcherRecoversTheTemplateTheRouterUsed() {
        RouteMatcher matcher = new RouteMatcher(List.of(
                new Route("GET", "/"),
                new Route("GET", "/books"),
                new Route("GET", "/books/{id}"),
                new Route("GET", "/api/books/search"),
                new Route("GET", "/api/books/{id}"),
                new Route("GET", "/api/books/{id}/missing"),
                new Route("POST", "/api/reviews"),
                new Route("GET", "/files/{path*}")));

        assertThat(matcher.match("GET", "/")).isEqualTo("/");
        assertThat(matcher.match("GET", "/books")).isEqualTo("/books");
        assertThat(matcher.match("GET", "/books/12")).isEqualTo("/books/{id}");
        assertThat(matcher.match("GET", "/books/12/")).isEqualTo("/books/{id}");
        assertThat(matcher.match("GET", "/api/books/9")).isEqualTo("/api/books/{id}");
        assertThat(matcher.match("GET", "/api/books/9/missing"))
                .isEqualTo("/api/books/{id}/missing");
        assertThat(matcher.match("GET", "/files/docs/a.txt")).isEqualTo("/files/{path*}");
        assertThat(matcher.match("POST", "/api/reviews")).isEqualTo("/api/reviews");

        // A literal beats a variable where both fit, whatever the registration order.
        assertThat(matcher.match("GET", "/api/books/search")).isEqualTo("/api/books/search");
        // Method and shape both have to match.
        assertThat(matcher.match("GET", "/api/reviews")).isNull();
        assertThat(matcher.match("GET", "/books/12/extra")).isNull();
        assertThat(matcher.match("GET", "/nope")).isNull();
    }

    @Test
    void everyRouteIsRegisteredWithADescription() {
        assertThat(app.routes()).isNotEmpty();
        assertThat(app.routes()).allSatisfy(route ->
                assertThat(route.description()).as(route.path()).isNotEmpty());
    }
}
