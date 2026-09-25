package net.benelog.spidersense.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerdictTest {

    @Test
    void nullPassIsNoVerdict() {
        assertThat(Verdict.of(true)).isEqualTo(Verdict.PASS);
        assertThat(Verdict.of(false)).isEqualTo(Verdict.FAIL);
        assertThat(Verdict.of(null)).isEqualTo(Verdict.NONE);
    }

    @Test
    void eachRenderingHasItsOwnWord() {
        assertThat(Verdict.PASS.word()).isEqualTo("pass");
        assertThat(Verdict.FAIL.word()).isEqualTo("fail");
        assertThat(Verdict.NONE.word()).isEqualTo("no verdict");
        assertThat(Verdict.PASS.header()).isEqualTo("true");
        assertThat(Verdict.FAIL.header()).isEqualTo("false");
        assertThat(Verdict.NONE.header()).isEqualTo("none");
    }

    @Test
    void passIsTheInverseOfOf() {
        for (Verdict verdict : Verdict.values()) {
            assertThat(Verdict.of(verdict.pass())).isEqualTo(verdict);
        }
    }
}
