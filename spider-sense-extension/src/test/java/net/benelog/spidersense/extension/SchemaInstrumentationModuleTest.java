package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.benelog.spidersense.extension.schema.IndexCatalog;
import org.junit.jupiter.api.Test;

/**
 * The helper list the module hands the agent, which is written out by hand: a class of the schema
 * package missing from it is a {@code NoClassDefFoundError} inside the application, which only the
 * real agent would otherwise notice, and only on the path that loads the class.
 */
class SchemaInstrumentationModuleTest {

    private final List<String> helpers = new SchemaInstrumentationModule().getAdditionalHelperClassNames();

    @Test
    void everyNestedClassOfTheCatalogIsAHelper() {
        List<String> nested = Arrays.stream(IndexCatalog.class.getDeclaredClasses())
                .map(Class::getName)
                .toList();

        assertThat(nested).isNotEmpty();
        assertThat(helpers).containsAll(nested);
    }

    /** The package as compiled, so a top-level helper beside the catalog is caught as well. */
    @Test
    void everyClassOfTheSchemaPackageIsAHelper() throws IOException, URISyntaxException {
        Path root = Path.of(IndexCatalog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String packagePath = IndexCatalog.class.getPackageName().replace('.', '/');
        List<String> compiled;
        try (Stream<Path> files = Files.list(root.resolve(packagePath))) {
            compiled = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".class") && !name.equals("package-info.class"))
                    .map(name -> IndexCatalog.class.getPackageName() + "."
                            + name.substring(0, name.length() - ".class".length()))
                    .toList();
        }

        assertThat(compiled).contains(IndexCatalog.class.getName());
        assertThat(helpers).containsExactlyInAnyOrderElementsOf(compiled);
    }

    /** The agent defines them in the order given, and a nested class needs its outer one first. */
    @Test
    void anOuterClassComesBeforeItsNestedOnes() {
        for (int i = 0; i < helpers.size(); i++) {
            String name = helpers.get(i);
            int dollar = name.lastIndexOf('$');
            if (dollar > 0) {
                assertThat(helpers.subList(0, i)).as(name).contains(name.substring(0, dollar));
            }
        }
    }
}
