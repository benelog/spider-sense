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

        boolean first = registry.recordSighting("orders", Map.of("process.pid", OWN_PID));

        assertThat(first).as("the name was in the database already").isFalse();
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

    private void storeService(String name, long pid) {
        sql.update(ServiceRow.INSERT, List.of(name, "java", pid, 1_000L, 1_000L,
                "{\"process.pid\":" + pid + "}"));
    }
}
