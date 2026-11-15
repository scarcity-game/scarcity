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
public record AuthProperties(@Valid Jwt jwt, @Valid Password password, @Valid ServiceToken serviceToken) {

    public AuthProperties {
        jwt = jwt != null ? jwt : new Jwt(null, Duration.ofHours(1));
        password = password != null ? password : new Password(12);
        serviceToken = serviceToken != null ? serviceToken : new ServiceToken(null, Duration.ofMinutes(15));
    }

    /**
     * @param privateKeyPath PKCS#8 PEM RSA private key; blank means "generate an ephemeral key"
     * @param ttl token lifetime
     */
    public record Jwt(String privateKeyPath, @NotNull @DefaultValue("1h") Duration ttl) {
    }

    /**
     * Service principals exchanging an API key for a JWT ({@code POST /api/auth/service-token}).
     *
     * @param keysDir one file per principal (name = principal, content = key); blank means no keys
     * @param ttl the service token's lifetime
     */
    public record ServiceToken(String keysDir, @NotNull @DefaultValue("15m") Duration ttl) {
    }

    /** @param bcryptStrength bcrypt cost factor, 10 to 31 */
    public record Password(@Min(10) @Max(31) @DefaultValue("12") int bcryptStrength) {
    }
}
