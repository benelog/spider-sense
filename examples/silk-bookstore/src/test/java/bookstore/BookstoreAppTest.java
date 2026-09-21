package bookstore;

import javax.sql.DataSource;

import bookstore.service.Seeder;
import net.benelog.spidersilk.App;
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
    void severalBooksInOneCall() {
        WebTest.test(app, client -> {
            var response = client.get("/api/books?ids=1,2,3");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .as("one round trip instead of three")
                    .contains("\"id\":1")
                    .contains("\"id\":2")
                    .contains("\"id\":3");
        });
    }

    @Test
    void anIdThatIsNotANumberAnswers400() {
        WebTest.test(app, client -> {
            var response = client.get("/api/books?ids=1,nope");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("Not a book id");
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

    /** The 500 the flaky endpoint answers carries the same JSON problem shape as every other API error. */
    @Test
    void aServerErrorIsAJsonProblemToo() {
        WebTest.test(app, client -> {
            for (int i = 0; i < 40; i++) {
                var response = client.get("/api/flaky");
                if (response.statusCode() == 500) {
                    assertThat(response.body()).contains("\"error\"");
                    return;
                }
            }
        });
    }

    @Test
    void everyRouteIsRegisteredWithADescription() {
        assertThat(app.routes()).isNotEmpty();
        assertThat(app.routes()).allSatisfy(route ->
                assertThat(route.description()).as(route.path()).isNotEmpty());
    }
}
