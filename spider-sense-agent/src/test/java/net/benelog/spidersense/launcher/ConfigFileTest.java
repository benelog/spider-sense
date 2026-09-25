package net.benelog.spidersense.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    private static final List<String> KEYS = List.of(
            ConfigFile.PROPERTY,
            "spidersense.port",
            "spidersense.host",
            "spidersense.service",
            "spidersense.slow.query.ms",
            "spidersense.ignore.endpoints",
            "spidersense.source.dirs",
            "spidersense.prot");

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        KEYS.forEach(System::clearProperty);
    }

    private Path file(String... lines) throws IOException {
        Path file = dir.resolve("my.properties");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void theNamedFileFillsInTheSystemProperties() throws IOException {
        Path file = file("spidersense.port = 4321", "spidersense.service=orders",
                "spidersense.slow.query.ms=50", "otel.service.name=not-ours");
        System.setProperty(ConfigFile.PROPERTY, file.toString());

        assertThat(ConfigFile.apply()).isEqualTo(file);

        assertThat(System.getProperty("spidersense.port")).isEqualTo("4321");
        assertThat(System.getProperty("spidersense.service")).isEqualTo("orders");
        assertThat(System.getProperty("spidersense.slow.query.ms")).isEqualTo("50");
        assertThat(System.getProperty("otel.service.name"))
                .as("keys without the prefix are the OpenTelemetry agent's to read, not ours")
                .isNull();
        assertThat(Config.fromSystemProperties().port()).isEqualTo(4321);
    }

    @Test
    void aSystemPropertyWinsOverTheFile() throws IOException {
        Path file = file("spidersense.port=4321", "spidersense.host=0.0.0.0");
        System.setProperty(ConfigFile.PROPERTY, file.toString());
        System.setProperty("spidersense.port", "4000");

        ConfigFile.apply();

        assertThat(System.getProperty("spidersense.port")).isEqualTo("4000");
        assertThat(System.getProperty("spidersense.host")).isEqualTo("0.0.0.0");
    }

    /** An exported but empty variable is unset to every reader, so the file's value stands. */
    @Test
    void anEmptyEnvironmentVariableHidesNothingAndASetOneWins() throws IOException {
        Path file = file("spidersense.port=4001", "spidersense.host=0.0.0.0");
        java.util.Properties properties = new java.util.Properties();
        try (var in = java.nio.file.Files.newBufferedReader(file)) {
            properties.load(in);
        }

        ConfigFile.apply(properties, file,
                name -> Map.of("SPIDERSENSE_PORT", "", "SPIDERSENSE_HOST", "127.0.0.1").get(name));

        assertThat(System.getProperty("spidersense.port")).isEqualTo("4001");
        assertThat(System.getProperty("spidersense.host")).as("a set variable still wins").isNull();
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
        System.setProperty(ConfigFile.PROPERTY, file.toString());
        System.setProperty("spidersense.port", "");
        System.setProperty("spidersense.service", "");
        System.setProperty("spidersense.ignore.endpoints", "");
        System.setProperty("spidersense.source.dirs", "");

        ConfigFile.apply();

        assertThat(System.getProperty("spidersense.port")).isEqualTo("4001");
        assertThat(System.getProperty("spidersense.service")).isEqualTo("orders");
        assertThat(Config.fromSystemProperties().port()).isEqualTo(4001);
        assertThat(System.getProperty("spidersense.ignore.endpoints")).as("ignore nothing").isEmpty();
        assertThat(System.getProperty("spidersense.source.dirs")).as("no root").isEmpty();
    }

    @Test
    void anEmptyValueIsKeptBecauseItMeansIgnoreNothing() throws IOException {
        Path file = file("spidersense.ignore.endpoints=");
        System.setProperty(ConfigFile.PROPERTY, file.toString());

        ConfigFile.apply();

        assertThat(System.getProperty("spidersense.ignore.endpoints")).isEmpty();
    }

    @Test
    void aKeyThatIsNotInTheTableIsAppliedWithAWarning() throws IOException {
        Path file = file("spidersense.prot=4321");
        System.setProperty(ConfigFile.PROPERTY, file.toString());
        String stderr = capturingStderr(ConfigFile::apply);

        assertThat(System.getProperty("spidersense.prot")).isEqualTo("4321");
        assertThat(stderr).contains("spidersense.prot").contains("not a Spider Sense property");
    }

    @Test
    void aNamedFileThatIsNotThereIsAWarningAndNothingElse() {
        System.setProperty(ConfigFile.PROPERTY, dir.resolve("missing.properties").toString());
        String stderr = capturingStderr(() -> assertThat(ConfigFile.apply()).isNull());

        assertThat(stderr).contains("missing.properties").contains("not a file");
        assertThat(System.getProperty("spidersense.port")).isNull();
    }

    @Test
    void noFileMeansNothingHappens() {
        // No spidersense.config, and no spider-sense.properties in the test's working directory.
        assertThat(Files.exists(Path.of(ConfigFile.DEFAULT_NAME))).isFalse();
        assertThat(ConfigFile.apply()).isNull();
        assertThat(Config.fromSystemProperties()).isEqualTo(Config.defaults());
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
