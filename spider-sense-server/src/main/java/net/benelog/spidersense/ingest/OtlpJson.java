package net.benelog.spidersense.ingest;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
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
     * The id members, in both spellings, and the length a hex value of each has: a trace id is 16
     * bytes, a span id 8. The one table the tree walk and the text pass both read.
     */
    private static final Map<String, Integer> ID_HEX_LENGTH = Map.of(
            "traceId", 32, "trace_id", 32,
            "spanId", 16, "span_id", 16,
            "parentSpanId", 16, "parent_span_id", 16);

    /**
     * Merges an OTLP/JSON document into {@code builder}, accepting hex ids and
     * base64 ids alike.
     *
     * @throws InvalidProtocolBufferException when the document is not OTLP/JSON at all
     */
    public static void merge(String body, Message.Builder builder) throws InvalidProtocolBufferException {
        String json = withoutLoneSurrogates(body);
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
     * The document with every escape of an unpaired surrogate (a backslash, {@code u} and four
     * hex digits) replaced by the escape of U+FFFD, the replacement character.
     *
     * <p>JSON text may escape half of a surrogate pair on its own, which protobuf's binary form
     * cannot carry: UTF-8 has no encoding for it. Kept, it would be stored as it came, every
     * answer written as UTF-8 would show {@code ?} where the store holds the surrogate, and an
     * export would no longer match the rows it came from on import. Replaced here, once for
     * every string of the document (a name, a key, a body, an attribute), what is stored is what
     * every answer carries. A raw surrogate cannot occur: the body was decoded from UTF-8, which
     * turned any malformed bytes into the replacement character already.
     */
    static String withoutLoneSurrogates(String json) {
        if (json.indexOf("\\u") < 0) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length());
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c != '\\' || i + 1 >= json.length()) {
                out.append(c);
                i++;
                continue;
            }
            int unit = escapedUnit(json, i);
            if (unit < 0) {
                // Any other escape, an escaped backslash among them, is copied whole, so its
                // second character is never read as the start of an escape.
                out.append(c).append(json.charAt(i + 1));
                i += 2;
            } else if (Character.isHighSurrogate((char) unit)
                    && Character.isLowSurrogate((char) Math.max(0, escapedUnit(json, i + 6)))) {
                out.append(json, i, i + 12);
                i += 12;
            } else if (Character.isSurrogate((char) unit)) {
                out.append("\\ufffd");
                i += 6;
            } else {
                out.append(json, i, i + 6);
                i += 6;
            }
        }
        return out.toString();
    }

    /** The UTF-16 unit a six-character escape at {@code at} stands for, or -1 when none is there. */
    private static int escapedUnit(String json, int at) {
        if (at + 6 > json.length() || json.charAt(at) != '\\' || json.charAt(at + 1) != 'u') {
            return -1;
        }
        int unit = 0;
        for (int i = at + 2; i < at + 6; i++) {
            int digit = Character.digit(json.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            unit = unit * 16 + digit;
        }
        return unit;
    }

    /**
     * An id member in the text: its key, and a value that may be hex. Inside a string value
     * every quote is escaped, so a key followed by an unescaped quote is a real member.
     */
    private static final Pattern ID_MEMBER = Pattern.compile(
            "\"(" + String.join("|", new TreeSet<>(ID_HEX_LENGTH.keySet()))
                    + ")\"(\\s*:\\s*)\"([0-9A-Fa-f]{16,32})\"");

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
        return ID_HEX_LENGTH.containsKey(key);
    }

    private static String convertIfHex(String key, String value) {
        Integer hexLength = ID_HEX_LENGTH.get(key);
        if (hexLength == null || value.length() != hexLength || !isHex(value)) {
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
