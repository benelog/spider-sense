package net.benelog.spidersense.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The name a service gets when nothing else named it, and when it gets none. */
class ServiceNameDefaultTest {

    private final ServiceNameDefault provider = new ServiceNameDefault();

    @Test
    void aJarIsNamedWithoutItsVersion() {
        assertThat(derive("build/libs/orders-0.1.0.jar --server.port=9000", null)).isEqualTo("orders");
        assertThat(derive("/apps/spring-orders-0.1.0.jar", null)).isEqualTo("spring-orders");
        assertThat(derive("app-1.0-SNAPSHOT.jar", null)).isEqualTo("app");
        assertThat(derive("app.jar", null)).isEqualTo("app");
        assertThat(derive("log4j-api-2.20.0.RELEASE.jar", null)).isEqualTo("log4j-api");
        assertThat(derive("1.0.jar", null)).as("nothing but a version keeps it").isEqualTo("1.0");
    }

    @Test
    void aJarWhosePathHoldsASpaceIsTheFileThatExists() {
        String name = ServiceNameDefault.derive("/my apps/orders-2.jar --verbose", null,
                path -> path.equals("/my apps/orders-2.jar"), (entry, file) -> false);
        assertThat(name).isEqualTo("orders");
    }

    @Test
    void aMainClassIsNamedAfterTheClassPathEntryThatHoldsIt() {
        String classPath = String.join(File.pathSeparator,
                "/opt/bookstore/lib/spider-silk-core-0.3.0.jar",
                "/opt/bookstore/lib/silk-bookstore-0.1.0.jar");
        assertThat(ServiceNameDefault.derive("bookstore.BookstoreApp", classPath, path -> false,
                (entry, file) -> entry.endsWith("silk-bookstore-0.1.0.jar")
                        && file.equals("bookstore/BookstoreApp.class")))
                .as("an installDist start script").isEqualTo("silk-bookstore");

        String classes = "/work/orders/build/classes/java/main" + File.pathSeparator + "/lib/a.jar";
        assertThat(ServiceNameDefault.derive("com.acme.Main arg.jar", classes, path -> false,
                (entry, file) -> entry.contains("/build/")))
                .as("the project a classes directory belongs to; an argument ending in .jar is not the jar")
                .isEqualTo("orders");
        assertThat(ServiceNameDefault.derive("com.acme.Main", "/work/target/classes", path -> false,
                (entry, file) -> true)).isEqualTo("work");
        assertThat(ServiceNameDefault.derive("com.acme.Main", "/elsewhere", path -> false,
                (entry, file) -> true)).as("a directory outside any build").isEqualTo("Main");
        assertThat(ServiceNameDefault.derive("com.acme.Main", null, path -> false, (entry, file) -> false))
                .isEqualTo("Main");
        assertThat(ServiceNameDefault.derive("orders.module/com.acme.Main", null, path -> false,
                (entry, file) -> false)).as("-m module/class").isEqualTo("Main");
    }

    @Test
    void noCommandIsNoName() {
        assertThat(derive(null, null)).isNull();
        assertThat(derive("  ", null)).isNull();
    }

    /** Only when the service is still the agent's default and nobody configured one. */
    @Test
    void itAppliesOnlyWhereNothingNamedTheService() {
        Resource unnamed = Resource.create(Attributes.of(ServiceNameDefault.SERVICE_NAME, "unknown_service:java"));
        Resource named = Resource.create(Attributes.of(ServiceNameDefault.SERVICE_NAME, "spring-orders"));

        assertThat(provider.shouldApply(config(Map.of()), unnamed)).isTrue();
        assertThat(provider.shouldApply(config(Map.of()), named))
                .as("spring.application.name or Implementation-Title came first").isFalse();
        assertThat(provider.shouldApply(config(Map.of("otel.service.name", "orders")), unnamed)).isFalse();
        assertThat(provider.shouldApply(config(Map.of("otel.resource.attributes", "service.name=orders")), unnamed))
                .isFalse();
        assertThat(provider.shouldApply(config(Map.of("otel.resource.attributes", "deployment.environment=dev")),
                unnamed)).isTrue();
        assertThat(provider.order()).as("after Spring Boot (100) and the manifest (300), before the jar name (1000)")
                .isBetween(301, 999);
    }

    @Test
    void itIsRegisteredAsAResourceProvider() throws Exception {
        try (InputStream in = ServiceNameDefault.class.getResourceAsStream(
                "/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim())
                    .isEqualTo(ServiceNameDefault.class.getName());
        }
    }

    private static String derive(String command, String classPath) {
        return ServiceNameDefault.derive(command, classPath, path -> false, (entry, file) -> false);
    }

    private static DefaultConfigProperties config(Map<String, String> properties) {
        return DefaultConfigProperties.createFromMap(properties);
    }
}
