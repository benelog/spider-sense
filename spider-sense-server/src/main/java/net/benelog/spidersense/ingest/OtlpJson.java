package net.benelog.spidersense.ingest;

import java.util.Base64;
import java.util.HexFormat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import net.benelog.spidersilk.json.Json;

/**
 * OTLP/JSON, which is protobuf's JSON mapping with one deliberate difference:
 * the specification says trace and span ids travel as <em>hex</em>, while
 * protobuf's own mapping encodes a {@code bytes} field as base64.
 *
 * <p>{@link JsonFormat} only knows the protobuf rule, and it does not fail on a
 * hex id: 32 hex characters are also valid base64, so a hex trace id would decode
 * quietly into 24 meaningless bytes. Detecting the difference after the fact is
 * therefore not possible from an exception — it has to happen before parsing.
 *
 * <p>The rule that tells them apart is the length. A real base64 16-byte trace id
 * is 24 characters and a base64 8-byte span id is 12; only a hex id is exactly 32
 * or 16 characters of {@code [0-9a-f]}. So the pre-pass rewrites exactly those and
 * leaves everything else alone, which makes it a no-op for a base64 body.
 */
public final class OtlpJson {

    private OtlpJson() {
    }

    /**
     * Merges an OTLP/JSON document into {@code builder}, accepting hex ids and
     * base64 ids alike.
     *
     * @throws InvalidProtocolBufferException when the document is not OTLP/JSON at all
     */
    public static void merge(String json, Message.Builder builder) throws InvalidProtocolBufferException {
        try {
            JsonFormat.parser().ignoringUnknownFields().merge(hexIdsToBase64(json), builder);
        } catch (Json.JsonException e) {
            // Not JSON we could walk; let JsonFormat report what is wrong with it.
            builder.clear();
            JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
        }
    }

    /**
     * Rewrites {@code traceId}, {@code spanId} and {@code parentSpanId} string
     * values that are hex into base64. Any other value, including an already
     * base64 one, is copied unchanged.
     */
    public static String hexIdsToBase64(String json) {
        return rewrite(Json.parse(json)).toJson();
    }

    private static Json.JsonValue rewrite(Json.JsonValue value) {
        if (value instanceof Json.JsonObject object) {
            Json.JsonObject rewritten = Json.obj();
            for (var member : object) {
                String key = member.getKey();
                Json.JsonValue child = member.getValue();
                if (child.isString() && isIdField(key)) {
                    rewritten.put(key, convertIfHex(key, child.asString()));
                } else {
                    rewritten.put(key, rewrite(child));
                }
            }
            return rewritten;
        }
        if (value instanceof Json.JsonArray array) {
            Json.JsonArray rewritten = Json.arr();
            for (Json.JsonValue element : array) {
                rewritten.add(rewrite(element));
            }
            return rewritten;
        }
        return value;
    }

    private static boolean isIdField(String key) {
        return key.equals("traceId") || key.equals("spanId") || key.equals("parentSpanId");
    }

    private static String convertIfHex(String key, String value) {
        int hexLength = key.equals("traceId") ? 32 : 16;
        if (value.length() != hexLength || !isHex(value)) {
            return value;
        }
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value));
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
