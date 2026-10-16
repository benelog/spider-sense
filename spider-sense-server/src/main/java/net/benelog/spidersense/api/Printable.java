package net.benelog.spidersense.api;

/**
 * Text a terminal prints rather than obeys (cli.adoc#text-rendering).
 *
 * <p>A text answer quotes what the application recorded, and that is often what a request sent
 * it: an exception message carries a parameter that would not convert, a log line a response
 * body. Printed as it came, an ESC in it is an instruction to the developer's terminal (OSC 52
 * writes the clipboard, CSI 2J clears the screen), and so is a C1 control such as U+009B, a CSI on
 * its own. So every control character, C0, DEL and C1, is written the way JSON escapes it, a
 * backslash, a {@code u} and four hex digits ({@code u001b} for ESC), except the newline that
 * ends each line of the text.
 *
 * <p>It runs over a whole answer once it is rendered, where a {@link Reports.Report} is made and
 * where {@code tail} prints a line, rather than in each rendering: whatever a rendering quotes, a
 * cell, a statement, a frame or a name, no control character but the newline reaches a terminal.
 */
public final class Printable {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Printable() {
    }

    /** {@code text} with every control character but the newline escaped; the same string when it has none. */
    public static String text(String text) {
        int control = nextControl(text, 0);
        if (control < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int from = 0;
        for (; control >= 0; control = nextControl(text, from)) {
            char c = text.charAt(control);
            // Cc is U+0000 to U+001F and U+007F to U+009F: two hex digits after the 00.
            out.append(text, from, control).append('\\').append("u00")
                    .append(HEX[c >> 4 & 0xF]).append(HEX[c & 0xF]);
            from = control + 1;
        }
        return out.append(text, from, text.length()).toString();
    }

    /** The index of the first control character at or after {@code from} that is no newline, or -1. */
    private static int nextControl(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && Character.getType(c) == Character.CONTROL) {
                return i;
            }
        }
        return -1;
    }
}
