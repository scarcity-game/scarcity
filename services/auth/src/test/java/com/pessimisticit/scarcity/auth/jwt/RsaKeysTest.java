package com.pessimisticit.scarcity.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pessimisticit.scarcity.auth.jwt.RsaKeys.InvalidKeyFileException;

class RsaKeysTest {

    @TempDir
    Path dir;

    @Test
    void loadsPkcs8PemAndDerivesPublicKey() throws Exception {
        KeyPair original = TestKeys.rsa3072();
        Path file = dir.resolve("private.pem");
        Files.writeString(file, TestKeys.pkcs8Pem(original));

        KeyPair loaded = RsaKeys.loadPkcs8Pem(file);

        assertThat(loaded.getPrivate()).isInstanceOf(RSAPrivateCrtKey.class);
        assertThat(loaded.getPrivate().getEncoded()).isEqualTo(original.getPrivate().getEncoded());
        RSAPublicKey pub = (RSAPublicKey) loaded.getPublic();
        assertThat(pub).isEqualTo(original.getPublic());
        assertThat(pub.getModulus().bitLength()).isEqualTo(3072);
    }

    @Test
    void toleratesCrlfAndSurroundingText() throws Exception {
        String pem = "comment line\r\n" + TestKeys.pkcs8Pem(TestKeys.rsa3072()).replace("\n", "\r\n");
        assertThat(RsaKeys.fromPkcs8Pem(pem).getPublic()).isEqualTo(TestKeys.rsa3072().getPublic());
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> RsaKeys.loadPkcs8Pem(dir.resolve("nope.pem")))
                .isInstanceOf(InvalidKeyFileException.class)
                .hasMessageContaining("Cannot read");
    }

    @Test
    void garbageFails() throws Exception {
        Path file = dir.resolve("garbage.pem");
        Files.writeString(file, "not a key");
        assertThatThrownBy(() -> RsaKeys.loadPkcs8Pem(file))
                .isInstanceOf(InvalidKeyFileException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void corruptBodyFails() {
        String pem = "-----BEGIN PRIVATE KEY-----\nAAAAAAAA\n-----END PRIVATE KEY-----\n";
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem(pem)).isInstanceOf(InvalidKeyFileException.class);
    }

    @Test
    void pkcs1HeaderIsRejectedWithHint() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n";
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem(pem))
                .isInstanceOf(InvalidKeyFileException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void publicKeyPemIsRejected() {
        String pem = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n";
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem(pem)).isInstanceOf(InvalidKeyFileException.class);
    }

    @Test
    void tooSmallKeyIsRejected() {
        String pem = TestKeys.pkcs8Pem(RsaKeys.generate(1024));
        assertThatThrownBy(() -> RsaKeys.fromPkcs8Pem(pem))
                .isInstanceOf(InvalidKeyFileException.class)
                .hasMessageContaining("1024 bits");
    }
}
