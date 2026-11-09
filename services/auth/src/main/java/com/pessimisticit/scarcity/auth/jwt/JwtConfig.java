package com.pessimisticit.scarcity.auth.jwt;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pessimisticit.scarcity.auth.AuthProperties;

@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    TokenService tokenService(AuthProperties properties, Clock clock) {
        AuthProperties.Jwt jwt = properties.jwt();
        TokenService service = new TokenService(signingKey(jwt.privateKeyPath()), jwt.ttl(), clock);
        log.info("JWT signing key ready, kid={}", service.keyId());
        return service;
    }

    /**
     * Blank path: ephemeral key with a WARN. Set but unusable: throws, which fails startup
     * (never silently fall back to an ephemeral key in production).
     */
    static KeyPair signingKey(String privateKeyPath) {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            log.warn("AUTH_JWT_PRIVATE_KEY_PATH is not set: generating an EPHEMERAL RSA-{} signing key. "
                    + "Tokens will not survive a restart and replicas will not agree. Dev/test only.",
                    RsaKeys.EPHEMERAL_BITS);
            return RsaKeys.generate(RsaKeys.EPHEMERAL_BITS);
        }
        return RsaKeys.loadPkcs8Pem(Path.of(privateKeyPath.strip()));
    }
}
