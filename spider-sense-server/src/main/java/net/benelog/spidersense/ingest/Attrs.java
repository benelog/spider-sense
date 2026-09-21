package net.benelog.spidersense.ingest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import net.benelog.spidersilk.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * OTLP attribute values turned into the plain Java values the store keeps.
 *
 * <p>The store's value space is deliberately small — String, Long, Double,
 * Boolean and lists of those — because that is exactly what JSON has. The two
 * OTLP shapes that do not fit are flattened rather than modelled: bytes become
 * base64 and a nested key/value list becomes a JSON string, which is what the
 * span drawer would show anyway.
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
                Json.JsonObject object = Json.obj();
                for (KeyValue entry : value.getKvlistValue().getValuesList()) {
                    Object nested = value(entry.getValue());
                    if (nested != null) {
                        put(object, entry.getKey(), nested);
                    }
                }
                yield object.toJson();
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
        return value instanceof String text ? text : String.valueOf(value);
    }

    /** 32 or 16 lowercase hex characters, or null for the empty id OTLP uses to mean "none". */
    public static @Nullable String hex(ByteString id) {
        if (id.isEmpty()) {
            return null;
        }
        byte[] bytes = id.toByteArray();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void put(Json.JsonObject object, String key, Object value) {
        switch (value) {
            case null -> object.putNull(key);
            case String text -> object.put(key, text);
            case Long number -> object.put(key, number.longValue());
            case Double number -> object.put(key, number.doubleValue());
            case Boolean flag -> object.put(key, flag.booleanValue());
            default -> object.put(key, String.valueOf(value));
        }
    }
}
