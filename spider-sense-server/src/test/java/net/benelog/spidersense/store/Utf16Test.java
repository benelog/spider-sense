package net.benelog.spidersense.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Text as UTF-8 can carry it (api.adoc#status-and-ingest). */
class Utf16Test {

    @Test
    void aLoneHalfOfASurrogatePairIsTheReplacementCharacter() {
        assertThat(Utf16.wellFormed("a\ud800b")).isEqualTo("a�b");
        assertThat(Utf16.wellFormed("a\udc00b")).isEqualTo("a�b");
        assertThat(Utf16.wellFormed("\udc00\ud800")).as("a pair in the wrong order is two halves")
                .isEqualTo("��");
        assertThat(Utf16.wellFormed("x\ud800")).as("a high half at the end").isEqualTo("x�");
        assertThat(Utf16.wellFormed("\ud800😀")).as("a lone half before a pair")
                .isEqualTo("�😀");
    }

    @Test
    void wellFormedTextIsTheSameString() {
        String emoji = "order 😀 shipped";

        assertThat(Utf16.wellFormed(emoji)).isSameAs(emoji);
        assertThat(Utf16.wellFormed("")).isEmpty();
        assertThat(Utf16.wellFormedOrNull(null)).isNull();
    }
}
