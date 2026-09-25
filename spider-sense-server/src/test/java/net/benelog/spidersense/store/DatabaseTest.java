package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersense.TestStore;

/**
 * The two ways in: the server's open, which upgrades, and the CLI's, which refuses
 * to touch a file of another version because a running older server may own it.
 */
class DatabaseTest {

    @TempDir
    Path dir;

    @Test
    void theCliOpenRefusesAMissingFile() {
        Path file = dir.resolve("none.mv.db");
        assertThatThrownBy(() -> Database.openExisting("jdbc:h2:" + dir.resolve("none"), file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no Spider Sense database at " + file);
    }

    @Test
    void theCliOpenRefusesAnotherSchemaVersionWhereTheServerOpenUpgrades() {
        String url = TestStore.memoryUrl();
        try (Database first = Database.open(url, null)) {
            first.sql().update("UPDATE meta SET value = ? WHERE key = 'schema_version'",
                    List.of(String.valueOf(Schema.VERSION + 1)));
            first.sql().update("INSERT INTO mark (at_ms, name) VALUES (1, 'kept')", List.of());

            assertThatThrownBy(() -> Database.openExisting(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema version " + (Schema.VERSION + 1));
            assertThat(first.sql().count("SELECT COUNT(*) FROM mark", List.of()))
                    .as("a refused open leaves the tables alone").isEqualTo(1);

            try (Database upgraded = Database.open(url, null)) {
                assertThat(upgraded.sql().count("SELECT COUNT(*) FROM mark", List.of()))
                        .as("the server's open recreates the tables").isZero();
            }
        }
    }

    /**
     * A table of another version may lack a column an index of this one is on,
     * so the server's open drops the old tables before it creates the new ones.
     */
    @Test
    void theServerOpenUpgradesATableAnIndexOfThisVersionCannotBeCreatedOn() {
        String url = TestStore.memoryUrl();
        try (Database first = Database.open(url, null)) {
            first.sql().execute("DROP TABLE span", "CREATE TABLE span (id BIGINT)");
            first.sql().update("UPDATE meta SET value = ? WHERE key = 'schema_version'",
                    List.of(String.valueOf(Schema.VERSION - 1)));

            try (Database upgraded = Database.open(url, null)) {
                assertThat(upgraded.storage().fallback()).as("the file itself was upgraded").isFalse();
                assertThat(upgraded.sql().count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME = 'SPAN' AND COLUMN_NAME = 'START_MS'", List.of()))
                        .isEqualTo(1);
                String version = upgraded.sql().queryOne("SELECT value FROM meta WHERE key = 'schema_version'",
                        List.of(), rs -> rs.getString(1));
                assertThat(version).isEqualTo(String.valueOf(Schema.VERSION));
            }
        }
    }

    /** A refused open leaves the database as it found it, without a table of this version in it. */
    @Test
    void theCliOpenCreatesNothingInADatabaseOfAnotherVersion() {
        String url = TestStore.memoryUrl();
        try (Database first = Database.open(url, null)) {
            first.sql().execute("DROP TABLE db_table");
            first.sql().update("UPDATE meta SET value = ? WHERE key = 'schema_version'",
                    List.of(String.valueOf(Schema.VERSION + 1)));

            assertThatThrownBy(() -> Database.openExisting(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema version " + (Schema.VERSION + 1));
            assertThat(first.sql().count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE TABLE_NAME = 'DB_TABLE'", List.of())).isZero();
        }
    }

    /**
     * A corrupt file fails the same way on every try, and the open runs on the
     * monitored application's premain thread: it falls back at once, saying why.
     */
    @Test
    void aCorruptFileFallsBackAtOnceNamingTheCorruption() throws Exception {
        Path file = dir.resolve("junk.mv.db");
        byte[] junk = new byte[20_000];
        new java.util.Random(7).nextBytes(junk);
        java.nio.file.Files.write(file, junk);

        long started = System.nanoTime();
        try (Database database = Database.open("jdbc:h2:" + dir.resolve("junk") + ";AUTO_SERVER=TRUE", file)) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(database.storage().fallback()).isTrue();
            assertThat(database.storage().fallbackReason()).containsIgnoringCase("corrupted");
            assertThat(elapsedMs).as("no retry for a failure that cannot pass").isLessThan(5_000);
        }
    }

    @Test
    void theRaceOfTwoProcessesOpeningOneFileIsWhatIsRetried() {
        assertThat(Database.isOpenRace(new SQLException("Lock file recently modified", "HY000", 8000))).isTrue();
        assertThat(Database.isOpenRace(new Sql.SqlException("create",
                new SQLException("Table already exists", "42S01", 42101)))).isTrue();
        assertThat(Database.isOpenRace(new SQLException("File corrupted", "90030", 90030))).isFalse();
        assertThat(Database.isOpenRace(new IllegalStateException("no code"))).isFalse();
    }

    /**
     * Two sessions of one fresh engine creating the schema at once, which is what the
     * second of two applications starting together on a new file is. H2 reports the
     * loser of a {@code CREATE ... IF NOT EXISTS} in ways other than "already
     * exists", and each of them must be taken for the race it is, and outlasted by
     * trying again, not fall back to memory.
     */
    @Test
    void everyFailureOfTwoSessionsCreatingTheSchemaAtOnceIsTakenForTheRace() throws Exception {
        List<String> notRetried = new java.util.ArrayList<>();
        for (int round = 0; round < 200; round++) {
            org.h2.jdbcx.JdbcConnectionPool pool = org.h2.jdbcx.JdbcConnectionPool.create(
                    "jdbc:h2:mem:schema-race-" + round + ";NON_KEYWORDS=KEY,VALUE", "sa", "");
            try {
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Callable<RuntimeException> create = () -> {
                    start.await();
                    try {
                        Schema.create(new Sql(pool));
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(create);
                    var second = executor.submit(create);
                    for (RuntimeException failure : List.of(
                            java.util.Optional.ofNullable(first.get()),
                            java.util.Optional.ofNullable(second.get())).stream()
                            .flatMap(java.util.Optional::stream).toList()) {
                        if (!Database.isOpenRace(failure)) {
                            notRetried.add(String.valueOf(failure.getCause()));
                        }
                    }
                } finally {
                    executor.shutdownNow();
                }
                Schema.create(new Sql(pool));
            } finally {
                pool.dispose();
            }
        }
        assertThat(notRetried).isEmpty();
    }

    @Test
    void thePageCacheIsASixteenthOfTheHeapBetweenSixteenAndTwoHundredFiftySixMib() {
        long mib = 1024 * 1024;
        assertThat(Database.cacheKb(128 * mib)).as("never below H2's own").isEqualTo(16 * 1024);
        assertThat(Database.cacheKb(1024 * mib)).isEqualTo(64 * 1024);
        assertThat(Database.cacheKb(16L * 1024 * mib)).as("never above the cap").isEqualTo(256 * 1024);
    }

    @Test
    void theProcessThatOwnsAFileSizesItsPageCacheAndAUrlThatNamesOneKeepsIt() {
        String owned = "jdbc:h2:" + dir.resolve("owned") + ";NON_KEYWORDS=KEY,VALUE";
        try (Database database = Database.open(owned, null)) {
            assertThat(cacheSize(database))
                    .isEqualTo(String.valueOf(Database.cacheKb(Runtime.getRuntime().maxMemory())));
        }
        String named = "jdbc:h2:" + dir.resolve("named") + ";CACHE_SIZE=20000;NON_KEYWORDS=KEY,VALUE";
        try (Database database = Database.open(named, null)) {
            assertThat(cacheSize(database)).isEqualTo("20000");
        }
    }

    private static String cacheSize(Database database) {
        return database.sql().queryOne("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                + " WHERE SETTING_NAME = 'CACHE_SIZE'", List.of(), rs -> rs.getString(1));
    }

    /**
     * The second layer of {@code POST /api/sql}, asserted against H2 rather than
     * assumed: the escape hatch is only as read-only as this user is.
     */
    @Test
    void theReaderUserMaySelectAndNothingElse() throws SQLException {
        String url = TestStore.memoryUrl();
        try (Database database = Database.open(url, null)) {
            database.sql().update("INSERT INTO mark (at_ms, name) VALUES (1, 'kept')", List.of());

            try (Connection reader = database.reader();
                    Statement statement = reader.createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM mark")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).as("it may read").isEqualTo(1);
                }

                refused(statement, "INSERT INTO mark (at_ms, name) VALUES (2, 'no')",
                        "Not enough rights");
                refused(statement, "UPDATE mark SET name = 'no'", "Not enough rights");
                refused(statement, "DELETE FROM mark", "Not enough rights");
                refused(statement, "DROP TABLE mark", "Not enough rights");
                refused(statement, "ALTER TABLE span ADD COLUMN evil INT", "Not enough rights");
                refused(statement, "CREATE TABLE evil (a INT)", "Not enough rights");

                refused(statement, "SELECT FILE_WRITE('x', 'build/tmp/reader.txt')",
                        "Admin rights are required");
                refused(statement, "CALL CSVWRITE('build/tmp/reader.csv', 'SELECT 1')",
                        "Admin rights are required");
                refused(statement, "SELECT FILE_READ('build.gradle')", "Admin rights are required");
                refused(statement, "CALL LINK_SCHEMA('X', '', 'jdbc:h2:mem:elsewhere', 'sa', '', 'PUBLIC')",
                        "Admin rights are required");
                refused(statement, "RUNSCRIPT FROM 'build/tmp/reader.sql'", "Admin rights are required");
                refused(statement, "CREATE USER hacker PASSWORD 'x' ADMIN", "Admin rights are required");
            }

            assertThat(database.sql().count("SELECT COUNT(*) FROM mark", List.of()))
                    .as("nothing the reader tried got through").isEqualTo(1);
        }
    }

    private static void refused(Statement statement, String sql, String why) {
        assertThatThrownBy(() -> statement.execute(sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(why);
    }

    /** An application's own database, named by a mistaken --db, gets no table of ours. */
    @Test
    void theCliOpenCreatesNothingInADatabaseSpiderSenseNeverWrote() {
        String url = TestStore.memoryUrl();
        try (Database app = Database.open(url, null)) {
            app.sql().execute("DROP ALL OBJECTS", "CREATE TABLE orders (id BIGINT PRIMARY KEY)");

            assertThatThrownBy(() -> Database.openExisting(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no Spider Sense schema");
            assertThat(app.sql().count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE TABLE_SCHEMA = 'PUBLIC'", List.of())).isEqualTo(1);
        }
    }

    /**
     * A database an older Spider Sense created has no reader user, and the CLI's
     * open does not add one to a database a running server may own: {@code sql}
     * then says so instead of answering.
     */
    @Test
    void theCliOpenCreatesNoReaderUser() {
        String url = TestStore.memoryUrl();
        try (Database older = Database.open(url, null)) {
            older.sql().execute("DROP USER " + Schema.READER);
            try (Database cli = Database.openExisting(url, null)) {
                assertThatThrownBy(cli::reader)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("the database has no read-only user yet");
            }
        }
    }

    /**
     * A JDBC URL with no file behind it, such as {@code jdbc:h2:mem:}, has no existence to
     * check, and the CLI's open creates no schema in the empty database it gets: it refuses.
     */
    @Test
    void theCliOpenRefusesAnEmptyDatabaseAndCreatesNothingInIt() throws SQLException {
        String url = TestStore.memoryUrl();
        try (Connection keep = java.sql.DriverManager.getConnection(url, "sa", "")) {
            assertThatThrownBy(() -> Database.openExisting(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no Spider Sense schema");
            try (ResultSet tables = keep.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                tables.next();
                assertThat(tables.getLong(1)).isZero();
            }
        }
    }

    /** The settings a reader may not apply are dropped; the two it needs are kept. */
    @Test
    void theReaderUrlKeepsOnlyWhatANonAdministratorMaySet() {
        assertThat(Database.readerUrl("jdbc:h2:mem:x;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE"))
                .isEqualTo("jdbc:h2:mem:x;NON_KEYWORDS=KEY,VALUE");
        assertThat(Database.readerUrl("jdbc:h2:~/db/spider-sense/sense;AUTO_SERVER=TRUE"))
                .isEqualTo("jdbc:h2:~/db/spider-sense/sense;AUTO_SERVER=TRUE");
        assertThat(Database.readerUrl("jdbc:h2:~/db/sense")).isEqualTo("jdbc:h2:~/db/sense");
    }

    @Test
    void theCliOpenAcceptsTheCurrentVersion() {
        String url = TestStore.memoryUrl();
        try (Database first = Database.open(url, null);
                Database again = Database.openExisting(url, null)) {
            assertThat(again.sql().count("SELECT COUNT(*) FROM meta", List.of())).isPositive();
        }
    }
}
