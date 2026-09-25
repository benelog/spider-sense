package net.benelog.spidersense.ingest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import net.benelog.spidersense.store.AttrJson;
import org.jspecify.annotations.Nullable;

/**
 * OTLP attribute values turned into the plain Java values the store keeps.
 *
 * <p>The store's value space is deliberately small — String, Long, Double,
 * Boolean, and lists and maps of those — because that is exactly what JSON has.
 * Bytes, the one OTLP shape that does not fit, become base64. A key/value list is
 * a map, stored as a JSON object however deep it nests, and read back as that
 * object's JSON text, which is what the span drawer would show anyway.
 */
public final class Attrs {

    private Attrs() {
    }

    public static Map<String, Object> toMap(List<KeyValue> attributes) {
        if (attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>(attributes.size());
        for (KeyValue attribute : attributes) {
            Object value = value(attribute.getValue());
            if (value != null) {
                map.put(attribute.getKey(), value);
            }
        }
        return Map.copyOf(map);
    }

    /** Null for a value OTLP left unset, which the caller drops. */
    public static @Nullable Object value(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BYTES_VALUE -> Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
            case ARRAY_VALUE -> {
                List<Object> values = new ArrayList<>(value.getArrayValue().getValuesCount());
                for (AnyValue element : value.getArrayValue().getValuesList()) {
                    Object converted = value(element);
                    if (converted != null) {
                        values.add(converted);
                    }
                }
                yield List.copyOf(values);
            }
            case KVLIST_VALUE -> {
                // A map rather than its JSON text: text one level down would be stored
                // as a string inside the JSON, its quotes escaped.
                Map<String, Object> map = new LinkedHashMap<>();
                for (KeyValue entry : value.getKvlistValue().getValuesList()) {
                    Object nested = value(entry.getValue());
                    if (nested != null) {
                        map.put(entry.getKey(), nested);
                    }
                }
                yield Collections.unmodifiableMap(map);
            }
            default -> null;
        };
    }

    /** A log body: the string when it is one, the JSON rendering otherwise. */
    public static String bodyText(AnyValue body) {
        Object value = value(body);
        if (value == null) {
            return "";
        }
        return value instanceof String text ? text : AttrJson.toJson(value).toJson();
    }

    /** The length of a valid trace id in bytes; 32 hex characters, which {@code CHAR(32)} holds. */
    private static final int TRACE_ID_BYTES = 16;

    /** The length of a valid span id in bytes; 16 hex characters, which {@code CHAR(16)} holds. */
    private static final int SPAN_ID_BYTES = 8;

    /** A trace id as 32 lowercase hex characters, or null when it is not a valid one. */
    public static @Nullable String traceId(ByteString id) {
        return validId(id, TRACE_ID_BYTES);
    }

    /** A span id as 16 lowercase hex characters, or null when it is not a valid one. */
    public static @Nullable String spanId(ByteString id) {
        return validId(id, SPAN_ID_BYTES);
    }

    /**
     * The id in hex when it has the length OTLP prescribes and is not all zeros,
     * which the specification calls invalid; null otherwise, which is also what an
     * empty id means.
     *
     * <p>An id of another length would not fit its {@code CHAR} column, and the
     * failed insert would roll back every record of the flush, not only its own.
     */
    private static @Nullable String validId(ByteString id, int bytes) {
        if (id.size() != bytes) {
            return null;
        }
        for (int i = 0; i < bytes; i++) {
            if (id.byteAt(i) != 0) {
                return hex(id);
            }
        }
        return null;
    }

    private static String hex(ByteString id) {
        byte[] bytes = id.toByteArray();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
