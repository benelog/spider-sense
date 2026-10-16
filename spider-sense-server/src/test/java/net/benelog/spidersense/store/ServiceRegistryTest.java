package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersense.TestStore;

/** Which service the server runs inside, when the launcher was not told (api.adoc#status). */
class ServiceRegistryTest {

    private static final long OWN_PID = ProcessHandle.current().pid();

    private final Database database = Database.open(TestStore.memoryUrl(), null);
    private final Sql sql = database.sql();

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aStoredServiceOfAnEarlierRunIsAdoptedWhenItReportsThisProcessId() {
        storeService("orders", OWN_PID + 1);
        ServiceRegistry registry = new ServiceRegistry(sql, null);
        assertThat(registry.embeddedService()).as("the stored row names the earlier run's pid").isNull();

        registry.recordSighting("orders", Map.of("process.pid", OWN_PID));

        assertThat(registry.embeddedService()).isEqualTo("orders");
        assertThat(registry.isEmbedded("orders")).isTrue();
    }

    @Test
    void aSightingOfAnotherProcessIsNotAdopted() {
        storeService("orders", OWN_PID + 1);
        ServiceRegistry registry = new ServiceRegistry(sql, null);

        registry.recordSighting("orders", Map.of("process.pid", OWN_PID + 1));
        registry.recordSighting("billing", Map.of("process.pid", OWN_PID + 2));

        assertThat(registry.embeddedService()).isNull();
    }

    @Test
    void theFirstServiceAdoptedStaysTheEmbeddedOne() {
        ServiceRegistry registry = new ServiceRegistry(sql, null);

        registry.recordSighting("orders", Map.of("process.pid", OWN_PID));
        registry.recordSighting("orders-tests", Map.of("process.pid", OWN_PID));

        assertThat(registry.embeddedService()).isEqualTo("orders");
    }

    /** A row written by an import of an earlier version: the server still starts, and lists it. */
    @Test
    void aStoredResourceThatIsNotAnObjectReadsAsNone() {
        sql.update(ServiceRow.INSERT, List.of("orders", "java", 1L, 1_000L, 1_000L, "\"not an object\""));

        ServiceRegistry registry = new ServiceRegistry(sql, null);

        assertThat(registry.all()).singleElement().satisfies(service -> {
            assertThat(service.name()).isEqualTo("orders");
            assertThat(service.resource()).isEmpty();
        });
    }

    private void storeService(String name, long pid) {
        sql.update(ServiceRow.INSERT, List.of(name, "java", pid, 1_000L, 1_000L,
                "{\"process.pid\":" + pid + "}"));
    }
}
