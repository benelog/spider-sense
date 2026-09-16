package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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

    @Test
    void theCliOpenAcceptsTheCurrentVersion() {
        String url = TestStore.memoryUrl();
        try (Database first = Database.open(url, null);
                Database again = Database.openExisting(url, null)) {
            assertThat(again.sql().count("SELECT COUNT(*) FROM meta", List.of())).isPositive();
        }
    }
}
