package com.pessimisticit.scarcity.auth.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CredentialsPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "Alice", "a_b-c", "ABC123", "___", "---",
            "abcdefghijklmnopqrstuvwxyz012345"})
    void validUsernames(String username) {
        assertThat(CredentialsPolicy.checkUsername(username)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "abcdefghijklmnopqrstuvwxyz0123456", "has space", "dot.name",
            "émile", "名前です", "a@b", "tab\tx", "abc\n", "ab😀"})
    void invalidUsernames(String username) {
        assertThat(CredentialsPolicy.checkUsername(username)).isNotEmpty()
                .allMatch(e -> e.field().equals("username"));
    }

    @Test
    void nullUsername() {
        assertThat(CredentialsPolicy.checkUsername(null))
                .containsExactly(new CredentialsPolicy.FieldError("username", "is required"));
    }

    @Test
    void passwordLengthBoundsInCodePoints() {
        assertThat(CredentialsPolicy.checkPassword("1234567")).isNotEmpty();
        assertThat(CredentialsPolicy.checkPassword("12345678")).isEmpty();
        // 8 emoji = 8 code points (16 UTF-16 units, 32 UTF-8 bytes): valid.
        assertThat(CredentialsPolicy.checkPassword("😀".repeat(8))).isEmpty();
        // 7 emoji = 7 code points even though it's 14 UTF-16 units: too short.
        assertThat(CredentialsPolicy.checkPassword("😀".repeat(7))).isNotEmpty();
    }

    @Test
    void passwordByteLimit() {
        assertThat(CredentialsPolicy.checkPassword("a".repeat(72))).isEmpty();
        assertThat(CredentialsPolicy.checkPassword("a".repeat(73)))
                .singleElement()
                .satisfies(e -> assertThat(e.message()).contains("72 bytes"));
        // 24 x 3-byte chars = 72 bytes: ok. 25 = 75 bytes: rejected although only 25 characters.
        assertThat(CredentialsPolicy.checkPassword("€".repeat(24))).isEmpty();
        assertThat(CredentialsPolicy.checkPassword("€".repeat(25))).isNotEmpty();
        // 18 x 4-byte emoji = 72 bytes ok, 19 rejected.
        assertThat(CredentialsPolicy.checkPassword("😀".repeat(18))).isEmpty();
        assertThat(CredentialsPolicy.checkPassword("😀".repeat(19))).isNotEmpty();
    }

    @Test
    void passwordOver128CharactersReportsBothLimits() {
        assertThat(CredentialsPolicy.checkPassword("a".repeat(129))).hasSize(2);
    }

    @Test
    void passwordWithUnpairedSurrogateIsRejected() {
        assertThat(CredentialsPolicy.checkPassword("abcdefgh\uD800")).isNotEmpty();
    }

    @Test
    void passwordMayContainAnyOtherCharacters() {
        assertThat(CredentialsPolicy.checkPassword("pass word !\"#$%")).isEmpty();
    }

    @Test
    void nullPassword() {
        assertThat(CredentialsPolicy.checkPassword(null))
                .containsExactly(new CredentialsPolicy.FieldError("password", "is required"));
    }

    @Test
    void registrationCollectsAllErrors() {
        assertThat(CredentialsPolicy.checkRegistration(null, null)).hasSize(2);
    }
}
