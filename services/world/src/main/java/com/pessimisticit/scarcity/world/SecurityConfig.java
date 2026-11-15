package com.pessimisticit.scarcity.world;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every request under {@code /api/world/**} needs a JWT minted by auth (RS256, {@code iss}
 * checked, signature verified against auth's JWKS). Stateless; no CSRF, no redirects, a
 * bare 401 otherwise. A service principal sends {@code X-Api-Key} instead
 * ({@link ApiKeyFilter}, keys from {@code scarcity.world.api-keys-dir}). Actuator is on another
 * port and is not secured, as in auth.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Read once at startup; a malformed key directory fails the start. */
    @Bean
    ApiKeys apiKeys(@Value("${scarcity.world.api-keys-dir:}") String dir) {
        ApiKeys keys = ApiKeys.fromDirectory(dir);
        if (keys.isEmpty()) {
            LOG.info("no API keys configured: every X-Api-Key request is rejected");
        } else {
            LOG.info("API keys configured for principals {}", keys.principals());
        }
        return keys;
    }

    /**
     * The JWKS is fetched lazily, on the first token (Nimbus' remote JWK set), so starting
     * the service doesn't depend on auth being reachable.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${scarcity.world.jwt-issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeys keys,
            @Value("${scarcity.world.api-key-rejection-log-interval:10s}") Duration logInterval) throws Exception {
        http
                .addFilterBefore(new ApiKeyFilter(keys, logInterval), BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/world/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
