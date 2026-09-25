package net.benelog.spidersense.query;

import org.jspecify.annotations.Nullable;

/**
 * What a {@code check} decided: pass, fail, or none when there was no request to judge
 * (check.adoc#exit-codes).
 *
 * <p>Each rendering of it reads this value rather than the JSON: the text heading, the
 * {@code X-Spider-Sense-Pass} header, MCP's {@code structuredContent} and the CLI's exit code.
 */
public enum Verdict {
    PASS("pass", "true"),
    FAIL("fail", "false"),
    NONE("no verdict", "none");

    private final String word;
    private final String header;

    Verdict(String word, String header) {
        this.word = word;
        this.header = header;
    }

    /** {@code pass} as {@link Check.CheckResult} holds it: null is no verdict. */
    public static Verdict of(@Nullable Boolean pass) {
        return pass == null ? NONE : pass ? PASS : FAIL;
    }

    /** The verdict as the text heading writes it. */
    public String word() {
        return word;
    }

    /** The verdict as the {@code X-Spider-Sense-Pass} header carries it (api.adoc#check). */
    public String header() {
        return header;
    }

    /** The verdict as JSON's {@code pass} writes it: true, false, or null for none. */
    public @Nullable Boolean pass() {
        return this == NONE ? null : this == PASS;
    }
}
