package net.benelog.spidersense.ingest;

import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            // Not JSON Spider Silk's parser walks, such as a uint64 written as a number past
            // 2^63, which OTLP allows. JsonFormat may still read it, so the ids are rewritten in
            // the text instead; handed over as they are, hex ids would decode as base64 into
            // ids of the wrong length, and every span would be skipped with a 200.
            builder.clear();
            JsonFormat.parser().ignoringUnknownFields().merge(hexIdsToBase64InText(json), builder);
        }
    }

    /**
     * An id member in the text: its key, and a value that may be hex. Inside a string value
     * every quote is escaped, so a key followed by an unescaped quote is a real member.
     */
    private static final Pattern ID_MEMBER = Pattern.compile(
            "\"(traceId|trace_id|spanId|span_id|parentSpanId|parent_span_id)\"(\\s*:\\s*)\"([0-9A-Fa-f]{16,32})\"");

    /** {@link #hexIdsToBase64} over the text, for a document the tree cannot be built from. */
    static String hexIdsToBase64InText(String json) {
        Matcher member = ID_MEMBER.matcher(json);
        StringBuilder out = new StringBuilder(json.length());
        while (member.find()) {
            String key = member.group(1);
            member.appendReplacement(out, Matcher.quoteReplacement(
                    "\"" + key + "\"" + member.group(2) + "\"" + convertIfHex(key, member.group(3)) + "\""));
        }
        member.appendTail(out);
        return out.toString();
    }

    /**
     * Rewrites {@code traceId}, {@code spanId} and {@code parentSpanId} string
     * values that are hex into base64. Any other value, including an already
     * base64 one, is copied unchanged.
     *
     * <p>The snake-case spellings ({@code trace_id}, {@code span_id},
     * {@code parent_span_id}) are rewritten too: the specification asks for
     * lowerCamelCase, but {@link JsonFormat} accepts the proto field names as well,
     * and a hex id it reads as base64 would be 24 bytes of nonsense.
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
        return isTraceId(key) || key.equals("spanId") || key.equals("span_id")
                || key.equals("parentSpanId") || key.equals("parent_span_id");
    }

    private static boolean isTraceId(String key) {
        return key.equals("traceId") || key.equals("trace_id");
    }

    private static String convertIfHex(String key, String value) {
        int hexLength = isTraceId(key) ? 32 : 16;
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
