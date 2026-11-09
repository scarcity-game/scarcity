package com.pessimisticit.scarcity.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.pessimisticit.scarcity.auth.AuthProperties;
import com.pessimisticit.scarcity.auth.Clocks;

/** Startup behaviour of the signing key: env set -> must load; unset -> ephemeral. */
class JwtConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, Clocks.class, JwtConfig.class);

    @TempDir
    Path dir;

    @Test
    void blankPathGeneratesEphemeralKey() {
        runner.withPropertyValues("auth.jwt.private-key-path=")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(TokenService.class));
    }

    @Test
    void validPathLoadsThatKey() throws Exception {
        KeyPair keys = TestKeys.rsa3072();
        Path file = dir.resolve("private.pem");
        Files.writeString(file, TestKeys.pkcs8Pem(keys));
        String expectedKid = new TokenService(keys, java.time.Duration.ofHours(1), java.time.Clock.systemUTC()).keyId();

        runner.withPropertyValues("auth.jwt.private-key-path=" + file)
                .run(ctx -> assertThat(ctx.getBean(TokenService.class).keyId()).isEqualTo(expectedKid));
    }

    @Test
    void missingFileFailsStartup() {
        runner.withPropertyValues("auth.jwt.private-key-path=" + dir.resolve("missing.pem"))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining(RsaKeys.InvalidKeyFileException.class.getName())
                            .hasStackTraceContaining("Cannot read JWT private key file");
                });
    }

    @Test
    void invalidFileFailsStartup() throws Exception {
        Path file = dir.resolve("bad.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\nnope\n-----END PRIVATE KEY-----\n");
        runner.withPropertyValues("auth.jwt.private-key-path=" + file)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("Invalid JWT private key file");
                });
    }

    @Test
    void bcryptStrengthBelowTenFailsStartup() {
        runner.withPropertyValues("auth.password.bcrypt-strength=9")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
