package net.benelog.spidersense.store;

import java.util.Map;

/**
 * A service as the resource attributes describe it.
 *
 * @param resource every resource attribute of the last export seen from it
 * @param embedded whether this is the service the server is running inside (agent mode)
 */
public record ServiceInfo(String name, Map<String, Object> resource, long firstSeen, long lastSeen,
        boolean embedded) {

    public String language() {
        Object language = resource.get("telemetry.sdk.language");
        return language == null ? null : String.valueOf(language);
    }

    public Long pid() {
        Object pid = resource.get("process.pid");
        return pid instanceof Number n ? n.longValue() : null;
    }

}
