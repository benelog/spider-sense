package net.benelog.spidersense.store;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One log line, with the ids that correlate it to a trace.
 *
 * @param id       monotonically increasing, assigned on ingest; the UI pages on it
 * @param severity the OTLP severity number mapped to TRACE/DEBUG/INFO/WARN/ERROR/FATAL
 * @param logger   the instrumentation scope name, which is the logger name for a Java appender
 */
public record LogRecord(
        long id,
        long at,
        String service,
        String severity,
        int severityNumber,
        String body,
        @Nullable String logger,
        @Nullable String traceId,
        @Nullable String spanId,
        Map<String, Object> attributes) {

    /** The OTLP severity ranges; anything outside them has no text. */
    public static String severityText(int number) {
        if (number >= 21) {
            return "FATAL";
        }
        if (number >= 17) {
            return "ERROR";
        }
        if (number >= 13) {
            return "WARN";
        }
        if (number >= 9) {
            return "INFO";
        }
        if (number >= 5) {
            return "DEBUG";
        }
        if (number >= 1) {
            return "TRACE";
        }
        return "UNSPECIFIED";
    }

    /** The lowest severity number a name covers, so {@code severity=WARN} can mean "WARN and worse". */
    public static int severityFloor(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "TRACE" -> 1;
            case "DEBUG" -> 5;
            case "INFO" -> 9;
            case "WARN" -> 13;
            case "ERROR" -> 17;
            case "FATAL" -> 21;
            default -> 0;
        };
    }
}
