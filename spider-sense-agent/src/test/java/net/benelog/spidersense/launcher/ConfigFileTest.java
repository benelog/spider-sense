package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The properties file, applied to a map from a working directory in a temporary directory. */
class ConfigFileTest {

    @TempDir
    Path dir;

    /** The system properties the file is applied to. */
    private final Map<String, String> properties = new HashMap<>();

    /** The environment behind them. */
    private final Map<String, String> env = new HashMap<>();

    private final Settings settings = Settings.of(properties::get, env::get, properties::put);

    private Path file(String... lines) throws IOException {
        Path file = dir.resolve("my.properties");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private @Nullable Path apply() {
        return ConfigFile.apply(dir, settings);
    }

    /** What the launcher reads once the file is applied. */
    private Config config() {
        return Config.fromSystemProperties(properties::get, env::get);
    }

    @Test
    void theNamedFileFillsInTheSystemProperties() throws IOException {
        Path file = file("spidersense.port = 4321", "spidersense.service=orders",
                "spidersense.slow.query.ms=50", "otel.service.name=not-ours");
        properties.put(ConfigFile.PROPERTY, file.toString());

        assertThat(apply()).isEqualTo(file);

        assertThat(properties).containsEntry("spidersense.port", "4321")
                .containsEntry("spidersense.service", "orders")
                .containsEntry("spidersense.slow.query.ms", "50");
        assertThat(properties)
                .as("keys without the prefix are the OpenTelemetry agent's to read, not ours")
                .doesNotContainKey("otel.service.name");
        assertThat(config().port()).isEqualTo(4321);
    }

    @Test
    void theVariableNamesTheFileTooAndARelativePathIsFromTheWorkingDirectory() throws IOException {
        Path file = file("spidersense.port=4321");
        env.put("SPIDERSENSE_CONFIG", "my.properties");

        assertThat(apply()).isEqualTo(file);
        assertThat(properties).containsEntry("spidersense.port", "4321");
    }

    @Test
    void theDefaultFileIsReadFromTheWorkingDirectory() throws IOException {
        Path file = Files.writeString(dir.resolve(ConfigFile.DEFAULT_NAME), "spidersense.port=4321\n");

        assertThat(apply()).isEqualTo(file);
        assertThat(properties).containsEntry("spidersense.port", "4321");
    }

    @Test
    void aSystemPropertyWinsOverTheFile() throws IOException {
        Path file = file("spidersense.port=4321", "spidersense.host=0.0.0.0");
        properties.put(ConfigFile.PROPERTY, file.toString());
        properties.put("spidersense.port", "4000");

        apply();

        assertThat(properties).containsEntry("spidersense.port", "4000")
                .containsEntry("spidersense.host", "0.0.0.0");
    }

    /** An exported but empty variable is unset to every reader, so the file's value stands. */
    @Test
    void anEmptyEnvironmentVariableHidesNothingAndASetOneWins() throws IOException {
        Path file = file("spidersense.port=4001", "spidersense.host=0.0.0.0");
        Properties lines = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            lines.load(in);
        }
        env.put("SPIDERSENSE_PORT", "");
        env.put("SPIDERSENSE_HOST", "127.0.0.1");

        ConfigFile.apply(lines, file, settings);

        assertThat(properties).containsEntry("spidersense.port", "4001");
        assertThat(properties).as("a set variable still wins").doesNotContainKey("spidersense.host");
    }

    /**
     * {@code -Dspidersense.port=${SENSE_PORT}} with the variable unset is an empty property, which
     * every reader of the port takes as unset, so the file's value stands; an empty
     * {@code ignore.endpoints} or {@code source.dirs} is a value of its own and still wins.
     */
    @Test
    void anEmptyPropertyHidesTheFileOnlyWhereEmptyMeansSomething() throws IOException {
        Path file = file("spidersense.port=4001", "spidersense.service=orders",
                "spidersense.ignore.endpoints=/ping", "spidersense.source.dirs=src");
        properties.put(ConfigFile.PROPERTY, file.toString());
        properties.put("spidersense.port", "");
        properties.put("spidersense.service", "");
        properties.put("spidersense.ignore.endpoints", "");
        properties.put("spidersense.source.dirs", "");

        apply();

        assertThat(properties).containsEntry("spidersense.port", "4001")
                .containsEntry("spidersense.service", "orders");
        assertThat(config().port()).isEqualTo(4001);
        assertThat(properties.get("spidersense.ignore.endpoints")).as("ignore nothing").isEmpty();
        assertThat(properties.get("spidersense.source.dirs")).as("no root").isEmpty();
    }

    @Test
    void anEmptyValueIsKeptBecauseItMeansIgnoreNothing() throws IOException {
        Path file = file("spidersense.ignore.endpoints=");
        properties.put(ConfigFile.PROPERTY, file.toString());

        apply();

        assertThat(properties.get("spidersense.ignore.endpoints")).isEmpty();
    }

    @Test
    void aKeyThatIsNotInTheTableIsAppliedWithAWarning() throws IOException {
        Path file = file("spidersense.prot=4321");
        properties.put(ConfigFile.PROPERTY, file.toString());
        String stderr = capturingStderr(this::apply);

        assertThat(properties).containsEntry("spidersense.prot", "4321");
        assertThat(stderr).contains("spidersense.prot").contains("not a Spider Sense property");
    }

    @Test
    void aNamedFileThatIsNotThereIsAWarningAndNothingElse() {
        properties.put(ConfigFile.PROPERTY, dir.resolve("missing.properties").toString());
        String stderr = capturingStderr(() -> assertThat(apply()).isNull());

        assertThat(stderr).contains("missing.properties").contains("not a file");
        assertThat(properties).containsOnlyKeys(ConfigFile.PROPERTY);
    }

    @Test
    void noFileMeansNothingHappens() {
        // No spidersense.config, and no spider-sense.properties in the working directory.
        assertThat(apply()).isNull();
        assertThat(properties).isEmpty();
        assertThat(config()).isEqualTo(Config.defaults());
    }

    @Test
    void everyKeyOfTheTableIsKnown() throws IOException {
        assertThat(ConfigFile.KNOWN_KEYS).isEqualTo(KeyTest.manualTable().keySet());
    }

    private static String capturingStderr(Runnable body) {
        PrintStream previous = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(previous);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
