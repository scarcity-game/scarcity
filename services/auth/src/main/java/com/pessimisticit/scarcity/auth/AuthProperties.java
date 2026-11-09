package com.pessimisticit.scarcity.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code auth.*}. */
@ConfigurationProperties("auth")
public record AuthProperties(Jwt jwt, Password password) {

    public AuthProperties {
        jwt = jwt != null ? jwt : new Jwt(null, Duration.ofHours(1));
        password = password != null ? password : new Password(12);
    }

    /**
     * @param privateKeyPath PKCS#8 PEM RSA private key; blank means "generate an ephemeral key"
     * @param ttl token lifetime
     */
    public record Jwt(String privateKeyPath, @DefaultValue("1h") Duration ttl) {
    }

    /** @param bcryptStrength bcrypt cost factor, at least 10 */
    public record Password(@DefaultValue("12") int bcryptStrength) {

        public Password {
            if (bcryptStrength < 10 || bcryptStrength > 31) {
                throw new IllegalArgumentException(
                        "auth.password.bcrypt-strength must be between 10 and 31, was " + bcryptStrength);
            }
        }
    }
}
