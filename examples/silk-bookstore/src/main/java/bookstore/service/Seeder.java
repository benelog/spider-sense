package bookstore.service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Creates the schema and, the first time it runs, fills it with enough rows for
 * an unindexed query to be genuinely slow.
 *
 * <p>The row count is a constructor parameter so the tests can seed a handful
 * of books into an in-memory database while the application seeds 200,000 into
 * the file one. Books go in as batches of {@link #BATCH} inserts inside one
 * transaction, which keeps first start to a few seconds; a database that
 * already has books is left alone, so only the very first start pays it.
 */
public class Seeder {

    /** What the application seeds: enough that a full scan is measured in hundreds of ms. */
    public static final int DEFAULT_BOOKS = 200_000;

    private static final int BATCH = 1_000;
    private static final int AUTHORS = 200;
    private static final int REVIEWED_BOOKS = 100;
    private static final int REVIEWS_PER_BOOK = 20;

    private static final String[] FIRST_NAMES = {
            "Ada", "Björn", "Chidi", "Dara", "Elif", "Farid", "Grete", "Hana", "Ivo", "Jun",
            "Kaya", "Liv", "Mira", "Nils", "Oona", "Pia", "Quinn", "Rui", "Sora", "Tove"};
    private static final String[] LAST_NAMES = {
            "Ahlberg", "Brandt", "Castellan", "Duarte", "Eriksen", "Fontaine", "Gaddis",
            "Halloran", "Ishikawa", "Jovanovic"};
    private static final String[] ADJECTIVES = {
            "Silent", "Crimson", "Hollow", "Gilded", "Northern", "Patient", "Restless",
            "Salt", "Winter", "Amber", "Iron", "Quiet", "Distant", "Broken", "Bright"};
    private static final String[] NOUNS = {
            "Dragon", "Lighthouse", "Cartographer", "Orchard", "Ledger", "Harbour", "Signal",
            "Archive", "Meridian", "Clockwork", "Frontier", "Compass", "Chorus", "Atlas"};
    private static final String[] SUFFIXES = {
            "", " of Ash", " in Winter", ", Book One", " Revisited", " and Other Stories",
            " at Dusk", " of the South"};
    private static final String[] PHRASES = {
            "a slow burning story about people who stay", "notes from a country that no longer exists",
            "an argument with the past, conducted in letters", "the last summer before everything changed",
            "a dragon appears on page four and is never explained", "field recordings from a vanished coast",
            "two families, one ledger, and a hundred years", "what the cartographer left out of the map"};

    private final DataSource dataSource;
    private final int bookCount;

    public Seeder(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOKS);
    }

    public Seeder(DataSource dataSource, int bookCount) {
        this.dataSource = dataSource;
        this.bookCount = bookCount;
    }

    /** Schema, SLEEP alias, and the data — skipping whatever is already there. */
    public void seed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runSchema();
        registerSleep(jdbc);

        if (count(jdbc, "books") > 0) {
            return;
        }
        long start = System.currentTimeMillis();
        seedAuthors(jdbc);
        seedBooks(jdbc);
        seedReviews(jdbc);
        System.out.printf("Seeded %,d books and %,d reviews in %,d ms%n",
                bookCount, reviewCount(), System.currentTimeMillis() - start);
    }

    private void runSchema() {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the bookstore schema", e);
        }
    }

    /**
     * A SQL function that does nothing but take time, so one query is slow
     * whatever the machine does with the rest.
     *
     * <p>H2 2.x wants the method as a single-quoted string literal, and it wants
     * the parameter type spelled out: {@code java.lang.Thread.sleep} alone is
     * rejected, because {@code sleep(long)} and {@code sleep(Duration)} have the
     * same parameter count and H2 matches by count.
     */
    private void registerSleep(JdbcTemplate jdbc) {
        jdbc.execute("create alias if not exists sleep for 'java.lang.Thread.sleep(long)'");
    }

    private void seedAuthors(JdbcTemplate jdbc) {
        List<Object[]> rows = new ArrayList<>(AUTHORS);
        for (int i = 0; i < AUTHORS; i++) {
            rows.add(new Object[]{FIRST_NAMES[i % FIRST_NAMES.length] + " "
                    + LAST_NAMES[(i / FIRST_NAMES.length) % LAST_NAMES.length]
                    + (i >= FIRST_NAMES.length * LAST_NAMES.length ? " II" : "")});
        }
        jdbc.batchUpdate("insert into authors (name) values (?)", rows);
    }

    private void seedBooks(JdbcTemplate jdbc) {
        Random random = new Random(42);
        List<@Nullable String> authors =
                jdbc.queryForList("select name from authors order by id", String.class);
        List<Object[]> batch = new ArrayList<>(BATCH);
        String sql = """
                insert into books (isbn, title, author, price, published_year, description)
                values (?, ?, ?, ?, ?, ?)
                """;
        for (int i = 1; i <= bookCount; i++) {
            String title = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " "
                    + NOUNS[random.nextInt(NOUNS.length)]
                    + SUFFIXES[random.nextInt(SUFFIXES.length)] + " #" + i;
            batch.add(new Object[]{
                    "978-%010d".formatted(i),
                    title,
                    authors.get(random.nextInt(authors.size())),
                    5.0 + random.nextInt(4500) / 100.0,
                    1950 + random.nextInt(75),
                    description(random, title)});
            if (batch.size() == BATCH || i == bookCount) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
    }

    /** Long enough that scanning it costs something, and wordy enough to be searchable. */
    private String description(Random random, String title) {
        StringBuilder text = new StringBuilder(title).append(" — ");
        for (int i = 0; i < 4; i++) {
            text.append(PHRASES[random.nextInt(PHRASES.length)]).append(". ");
        }
        return text.toString();
    }

    private void seedReviews(JdbcTemplate jdbc) {
        Random random = new Random(7);
        long authors = count(jdbc, "authors");
        List<Object[]> rows = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.systemDefault()));
        for (long bookId = 1; bookId <= Math.min(REVIEWED_BOOKS, bookCount); bookId++) {
            for (int i = 0; i < REVIEWS_PER_BOOK; i++) {
                rows.add(new Object[]{bookId, 1 + random.nextInt((int) authors),
                        1 + random.nextInt(5),
                        PHRASES[random.nextInt(PHRASES.length)], now});
            }
        }
        jdbc.batchUpdate("""
                insert into reviews (book_id, author_id, rating, body, created_at)
                values (?, ?, ?, ?, ?)
                """, rows);
    }

    private int reviewCount() {
        return Math.min(REVIEWED_BOOKS, bookCount) * REVIEWS_PER_BOOK;
    }

    private long count(JdbcTemplate jdbc, String table) {
        Long count = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }
}
