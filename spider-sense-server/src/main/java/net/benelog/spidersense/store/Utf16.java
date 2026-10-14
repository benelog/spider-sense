package net.benelog.spidersense.store;

import org.jspecify.annotations.Nullable;

/**
 * Text as UTF-8 can carry it: every half of a surrogate pair that stands alone replaced by
 * U+FFFD, the replacement character (api.adoc#status-and-ingest).
 *
 * <p>A JSON string may escape half of a surrogate pair on its own ({@code "\ud800"}), and the
 * parser hands it over as one {@code char}. UTF-8 has no encoding for it, so kept as it came it
 * would be stored and then answered as {@code ?} by the text renderings and as the escape by the
 * JSON ones: one note, two readings. The OTLP/JSON receiver replaces it in the document's text
 * before protobuf reads it; what a person or an agent types, a mark's note, an acknowledgement's
 * note, a {@code sql} statement, is replaced here, before it is stored or run.
 */
public final class Utf16 {

    /** The replacement character. */
    private static final char REPLACEMENT = '�';

    private Utf16() {
    }

    /** {@code text} with every unpaired surrogate replaced by U+FFFD; the same string when it has none. */
    public static String wellFormed(String text) {
        int lone = firstLone(text, 0);
        if (lone < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length()).append(text, 0, lone).append(REPLACEMENT);
        int from = lone + 1;
        for (int next = firstLone(text, from); next >= 0; next = firstLone(text, from)) {
            out.append(text, from, next).append(REPLACEMENT);
            from = next + 1;
        }
        return out.append(text, from, text.length()).toString();
    }

    /** {@link #wellFormed(String)}, or null for null. */
    public static @Nullable String wellFormedOrNull(@Nullable String text) {
        return text == null ? null : wellFormed(text);
    }

    /** The index of the first unpaired surrogate at or after {@code from}, or -1 when there is none. */
    private static int firstLone(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isSurrogate(c)) {
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i += 2;
            } else {
                return i;
            }
        }
        return -1;
    }
}
