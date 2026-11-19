package com.pessimisticit.scarcity.auth.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordNormalizerTest {

    @Test
    void precomposedAndDecomposedAreEqual() {
        assertThat(PasswordNormalizer.normalize("é")).isEqualTo("é");
    }

    @Test
    void fullwidthFoldsToAscii() {
        assertThat(PasswordNormalizer.normalize("ｐａｓｓ")).isEqualTo("pass");
    }

    @Test
    void asciiIsUnchangedAndNothingElseIsTouched() {
        assertThat(PasswordNormalizer.normalize("  Pa ss\tWORD  ")).isEqualTo("  Pa ss\tWORD  ");
    }

    @Test
    void lengthIsCountedInCodePointsAfterNormalising() {
        // 7 x (e + combining acute) = 14 code points raw, 7 after NFKC: too short.
        String shortRaw = "é".repeat(7);
        assertThat(CredentialsPolicy.checkPassword(shortRaw)).isEmpty();
        assertThat(CredentialsPolicy.checkPassword(PasswordNormalizer.normalize(shortRaw))).isNotEmpty();
        assertThat(CredentialsPolicy.checkPassword(PasswordNormalizer.normalize("é".repeat(8)))).isEmpty();
        // An emoji is one code point.
        assertThat(CredentialsPolicy.checkPassword(PasswordNormalizer.normalize("😀".repeat(8)))).isEmpty();
        assertThat(CredentialsPolicy.checkPassword(PasswordNormalizer.normalize("😀".repeat(7)))).isNotEmpty();
    }
}
