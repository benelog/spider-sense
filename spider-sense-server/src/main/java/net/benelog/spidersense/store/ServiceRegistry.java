package net.benelog.spidersense.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Which services exist, and which one the server is embedded in.
 *
 * <p>The rows live in the {@code service} table — the writer merges them — but
 * "have I seen this name before?" is asked on every export and answered from an
 * in-memory set, so ingest does not query the database to decide whether to push
 * a {@code service} SSE event.
 *
 * <p>The embedded service is named on the command line when the launcher knows
 * it. In agent mode it usually does not — the OpenTelemetry agent decides
 * {@code otel.service.name} after our {@code premain} has run — so the first
 * service whose {@code process.pid} is this JVM's is taken instead. That is
 * exact: only the process we live in reports our own pid.
 */
public final class ServiceRegistry {

    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    private final Sql sql;
    private final @Nullable String configuredEmbedded;
    private final long ownPid;

    private volatile @Nullable String embedded;

    public ServiceRegistry(Sql sql, @Nullable String configuredEmbedded) {
        this.sql = sql;
        this.configuredEmbedded = configuredEmbedded;
        this.embedded = configuredEmbedded;
        this.ownPid = ProcessHandle.current().pid();
        for (ServiceInfo service : all()) {
            seen.put(service.name(), Boolean.TRUE);
            adoptIfOurOwnProcess(service.name(), service.resource());
        }
    }

    /**
     * Notes a sighting.
     *
     * @return true the first time this process sees the service, which is when the
     *         SSE stream sends a {@code service} event
     */
    public boolean seen(String name, Map<String, Object> resource) {
        boolean isNew = seen.putIfAbsent(name, Boolean.TRUE) == null;
        if (isNew && embedded == null) {
            adoptIfOurOwnProcess(name, resource);
        }
        return isNew;
    }

    private void adoptIfOurOwnProcess(String name, Map<String, Object> resource) {
        Object pid = resource.get("process.pid");
        if (pid instanceof Number n && n.longValue() == ownPid) {
            embedded = name;
        }
    }

    /** The service the server is embedded in, or null when it is standalone or not yet known. */
    public @Nullable String embeddedService() {
        return embedded;
    }

    public boolean isEmbedded(String name) {
        return name.equals(embedded) || name.equals(configuredEmbedded);
    }

    public @Nullable ServiceInfo get(String name) {
        return sql.queryOne("SELECT * FROM service WHERE name = ?", List.of(name), this::map);
    }

    /** Every service ever seen, sorted by name. */
    public List<ServiceInfo> all() {
        return new ArrayList<>(sql.query("SELECT * FROM service ORDER BY name", List.of(), this::map));
    }

    public long count() {
        return sql.count("SELECT COUNT(*) FROM service", List.of());
    }

    private ServiceInfo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String name = rs.getString("name");
        return new ServiceInfo(name, AttrJson.decode(rs.getString("resource")),
                rs.getLong("first_seen"), rs.getLong("last_seen"), isEmbedded(name));
    }
}
