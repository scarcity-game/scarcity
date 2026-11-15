package com.pessimisticit.scarcity.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Settings under {@code auth.*}, checked with Bean Validation at startup. */
@ConfigurationProperties("auth")
@Validated
public record AuthProperties(@Valid Jwt jwt, @Valid Password password) {

    public AuthProperties {
        jwt = jwt != null ? jwt : new Jwt(null, Duration.ofHours(1));
        password = password != null ? password : new Password(12);
    }

    /**
     * @param privateKeyPath PKCS#8 PEM RSA private key; blank means "generate an ephemeral key"
     * @param ttl token lifetime
     */
    public record Jwt(String privateKeyPath, @NotNull @DefaultValue("1h") Duration ttl) {
    }

    /** @param bcryptStrength bcrypt cost factor, 10 to 31 */
    public record Password(@Min(10) @Max(31) @DefaultValue("12") int bcryptStrength) {
    }
}
