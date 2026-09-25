package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The key table against the one in the manual it transcribes. */
class KeyTest {

    /** Where the table is, from the module directory the tests run in. */
    private static final Path CONFIGURATION =
            Path.of("..", "manual", "modules", "ROOT", "pages", "configuration.adoc");

    @Test
    void theDocumentedKeysAreTheManualsTable() throws IOException {
        assertThat(Key.documentedProperties()).isEqualTo(manualTable().keySet());
    }

    /**
     * What the help shows as a key's default is the manual's default, where the manual gives one
     * value; where it says unset or describes the default, the help shows nothing or a placeholder.
     */
    @Test
    void theHelpShowsTheManualsDefaults() throws IOException {
        Map<String, String> table = manualTable();
        for (Key key : Key.values()) {
            if (!key.documented()) {
                continue;
            }
            String documented = table.get(key.property());
            if (documented.matches("`[^`]*`")) {
                assertThat(key.shown()).as("%s", key)
                        .isEqualTo(documented.substring(1, documented.length() - 1));
            } else {
                assertThat(key.shown()).as("%s", key).matches("|<[a-z]+>");
            }
        }
    }

    /** The shown defaults of the launcher's own keys are what it reads when nothing is set. */
    @Test
    void theShownDefaultsParseToTheLaunchersDefaults() {
        Config shown = Config.parse(new String[] {
                "--port=" + Key.PORT.shown(),
                "--host=" + Key.HOST.shown(),
                "--slow.request.ms=" + Key.SLOW_REQUEST_MS.shown(),
                "--slow.query.ms=" + Key.SLOW_QUERY_MS.shown(),
                "--open=" + Key.OPEN.shown(),
                "--mode=" + Key.MODE.shown()}, key -> null, key -> null).config();

        assertThat(shown).isEqualTo(Config.defaults());
        assertThat(Key.DB.shown()).as("the server's, shown and never passed").isEqualTo(Config.DEFAULT_DB);
        assertThat(Key.RETENTION_HOURS.shown()).isEqualTo(String.valueOf(Config.DEFAULT_RETENTION_HOURS));
    }

    @Test
    void anArgumentNamesAKeyByItsNameOrItsAlias() {
        assertThat(Key.ofArgument("slow.query.ms")).isEqualTo(Key.SLOW_QUERY_MS);
        assertThat(Key.ofArgument("retention-hours")).isEqualTo(Key.RETENTION_HOURS);
        assertThat(Key.ofArgument("embedded-service")).isEqualTo(Key.SERVICE);
        assertThat(Key.ofArgument("prot")).isNull();
    }

    /**
     * The rows of configuration.adoc#properties: each property and its default cell as written,
     * backticks and all.
     */
    static Map<String, String> manualTable() throws IOException {
        List<String> lines = Files.readAllLines(CONFIGURATION, StandardCharsets.UTF_8);
        int start = lines.indexOf("| Property | Default | Meaning");
        assertThat(start).as("the properties table in " + CONFIGURATION).isNotNegative();
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).equals("|==="); i++) {
            String line = lines.get(i);
            if (line.startsWith("| `spidersense.")) {
                String property = line.substring(3, line.indexOf('`', 3));
                table.put(property, lines.get(i + 1).substring(2));
            }
        }
        return table;
    }
}
