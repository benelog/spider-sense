package net.benelog.spidersense.extension;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The extension's own instrumentation module, {@code spider-sense-schema}: the advice that reads
 * the index catalog of the tables a slow statement
 * touched ({@code design.adoc#extension}).
 *
 * <p>It exists because the catalog needs a {@link java.sql.Connection}, and a span processor never
 * has one: by the time a database span ends, the only thing left of the statement is its text. An
 * advice on {@link java.sql.Statement} runs while the connection is still borrowed, on the thread
 * that borrowed it, which is the one moment the question can be asked at all.
 *
 * <p>The module is found through
 * {@code META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule}
 * when the agent loads {@code spider-sense/extension.jar}. It declares no muzzle references — it
 * calls nothing but the JDBC API and the agent's own shaded API — so it always applies.
 */
public final class SchemaInstrumentationModule extends InstrumentationModule {

    /** The package the helper lives in; everything under it is injected beside the driver. */
    private static final String HELPER_PACKAGE = "net.benelog.spidersense.extension.schema.";

    /**
     * The helper classes, outer and superclasses first, because the agent injects them in the order
     * given and a class cannot be defined before the names it needs are there.
     *
     * <p>The list is written out rather than derived: the injector has no class path to scan, and a
     * nested class that is added later and forgotten here is a {@code NoClassDefFoundError} inside
     * the application, which is exactly what this extension may never cause.
     */
    private static final List<String> HELPERS = Collections.unmodifiableList(Arrays.asList(
            HELPER_PACKAGE + "IndexCatalog",
            HELPER_PACKAGE + "IndexCatalog$Word",
            HELPER_PACKAGE + "IndexCatalog$Index"));

    public SchemaInstrumentationModule() {
        super("spider-sense-schema");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return HELPERS;
    }

    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith(HELPER_PACKAGE);
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new StatementInstrumentation());
    }
}
