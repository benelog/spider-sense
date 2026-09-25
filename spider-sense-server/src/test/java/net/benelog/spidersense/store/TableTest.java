package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/** The one list of tables the schema bump, the retention and the empty go through. */
class TableTest {

    private final Database database = Database.open(TestStore.memoryUrl(), null);

    @AfterEach
    void close() {
        database.close();
    }

    /** A table the schema creates and this list misses would survive a version bump in its old shape. */
    @Test
    void everyTableTheSchemaCreatesButMetaIsListed() {
        List<String> created = database.sql().query("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> 'META'", List.of(), rs -> rs.getString(1));

        assertThat(Arrays.stream(Table.values()).map(Table::sqlName).toList())
                .containsExactlyInAnyOrderElementsOf(created);
    }

    /** storage.adoc#retention names the tables the sweeper deletes by age, in this order. */
    @Test
    void theRetentionSweepsTheTablesTheManualNames() {
        assertThat(Arrays.stream(Table.values()).filter(Table::sweptByAge).map(Table::sqlName))
                .containsExactly("span", "trace", "log", "metric_point", "tingle", "mark", "db_table");
        assertThat(Arrays.stream(Table.values()).filter(Table::cappedBySpans).map(Table::sqlName))
                .containsExactly("span", "trace", "log", "metric_point", "tingle");
        assertThat(Arrays.stream(Table.values()).filter(table -> table.clear() != null).map(Table::sqlName))
                .containsExactly("span", "trace", "log", "metric_point", "tingle", "mark", "ack", "db_table");
    }

    @Test
    void theAgeDeleteNamesTheTimeColumn() {
        assertThat(Table.DB_TABLE.deleteOlder()).isEqualTo("DELETE FROM db_table WHERE seen_ms < ?");
        assertThat(Table.MARK.deleteOlder().toLowerCase(Locale.ROOT)).contains("o.at_ms < ?");
    }
}
