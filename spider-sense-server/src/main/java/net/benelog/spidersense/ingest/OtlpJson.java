package net.benelog.spidersense.ingest;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonValue;
import net.benelog.silkjson.JsonObject;
import net.benelog.silkjson.JsonArray;
import net.benelog.silkjson.JsonException;
import org.jspecify.annotations.Nullable;

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
 * leaves base64 alone, which makes it a no-op for a base64 body.
 *
 * <p>An id that is neither, one with a stray space, of a length no base64 has, or
 * not a string at all, is emptied instead: {@link JsonFormat} would refuse the
 * whole export over it, and an empty id is one the decoder skips its span for, so
 * the rest of the export is stored (api.adoc#status-and-ingest).
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
        } catch (JsonException e) {
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
     *
     * <p>The rule is {@link net.benelog.spidersense.store.Utf16}'s, applied to the escapes of the
     * text because protobuf's parser, not Spider Silk's, reads them into strings.
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
     * {@link #hexIdsToBase64} over the text, for a document the tree cannot be built from.
     *
     * <p>The text is read as JSON tokens rather than matched, so a quoted key inside a string
     * value is never taken for a member: a string followed by a colon is a member's key, and
     * an id member's value, whatever it is, a string, a number, an object, is replaced whole.
     */
    static String hexIdsToBase64InText(String json) {
        StringBuilder out = new StringBuilder(json.length());
        int at = 0;
        while (at < json.length()) {
            if (json.charAt(at) != '"') {
                out.append(json.charAt(at));
                at++;
                continue;
            }
            int end = stringEnd(json, at);
            String key = json.substring(at + 1, Math.max(at + 1, end - 1));
            out.append(json, at, end);
            at = end;
            int colon = skipSpace(json, at);
            if (!isIdField(key) || colon >= json.length() || json.charAt(colon) != ':') {
                continue;
            }
            int start = skipSpace(json, colon + 1);
            int valueEnd = valueEnd(json, start);
            String value = stringValue(json.substring(start, valueEnd));
            out.append(json, at, start).append('"').append(value == null ? "" : idValue(key, value)).append('"');
            at = valueEnd;
        }
        return out.toString();
    }

    /** The index just past the string that opens at {@code at}, or the end of the text when it never closes. */
    private static int stringEnd(String json, int at) {
        int i = at + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return json.length();
    }

    private static int skipSpace(String json, int at) {
        int i = at;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * The index just past the value that starts at {@code at}: a string, an object or an array
     * read to its matching close, or a number or a literal read to the next separator.
     */
    private static int valueEnd(String json, int at) {
        if (at >= json.length()) {
            return at;
        }
        char first = json.charAt(at);
        if (first == '"') {
            return stringEnd(json, at);
        }
        int i = at;
        if (first == '{' || first == '[') {
            int depth = 0;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '"') {
                    i = stringEnd(json, i);
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    return i + 1;
                }
                i++;
            }
            return i;
        }
        while (i < json.length() && ",}]".indexOf(json.charAt(i)) < 0 && !Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The string a JSON string token stands for, or null when the token is no string. */
    private static @Nullable String stringValue(String token) {
        if (token.length() < 2 || token.charAt(0) != '"' || token.charAt(token.length() - 1) != '"') {
            return null;
        }
        try {
            JsonValue value = Json.parse(token);
            return value.isString() ? value.asString() : null;
        } catch (JsonException e) {
            return null;
        }
    }

    /**
     * Rewrites {@code traceId}, {@code spanId} and {@code parentSpanId} string
     * values that are hex into base64, and empties one that is neither hex of its
     * length nor base64, or no string at all. A base64 one is copied unchanged.
     *
     * <p>The snake-case spellings ({@code trace_id}, {@code span_id},
     * {@code parent_span_id}) are rewritten too: the specification asks for
     * lowerCamelCase, but {@link JsonFormat} accepts the proto field names as well,
     * and a hex id it reads as base64 would be 24 bytes of nonsense.
     */
    public static String hexIdsToBase64(String json) {
        return rewrite(Json.parse(json)).toJson();
    }

    private static JsonValue rewrite(JsonValue value) {
        if (value instanceof JsonObject object) {
            JsonObject rewritten = Json.object();
            for (var member : object) {
                String key = member.getKey();
                JsonValue child = member.getValue();
                if (isIdField(key)) {
                    rewritten.put(key, child.isString() ? idValue(key, child.asString()) : "");
                } else {
                    rewritten.put(key, rewrite(child));
                }
            }
            return rewritten;
        }
        if (value instanceof JsonArray array) {
            JsonArray rewritten = Json.array();
            for (JsonValue element : array) {
                rewritten.add(rewrite(element));
            }
            return rewritten;
        }
        return value;
    }

    private static boolean isIdField(String key) {
        return ID_HEX_LENGTH.containsKey(key);
    }

    /**
     * What an id member's string becomes: hex of the id's length as base64, base64 as it is,
     * and anything else empty.
     */
    private static String idValue(String key, String value) {
        Integer hexLength = ID_HEX_LENGTH.get(key);
        if (hexLength != null && value.length() == hexLength && isHex(value)) {
            return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value));
        }
        return decodesAsBase64(value) ? value : "";
    }

    /** Whether protobuf's JSON parser reads {@code value} as bytes: base64, or failing that, URL-safe base64. */
    private static boolean decodesAsBase64(String value) {
        try {
            Base64.getDecoder().decode(value);
            return true;
        } catch (IllegalArgumentException e) {
            try {
                Base64.getUrlDecoder().decode(value);
                return true;
            } catch (IllegalArgumentException alsoNot) {
                return false;
            }
        }
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
