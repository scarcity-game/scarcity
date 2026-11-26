package com.pessimisticit.scarcity.controller;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * {@code controller.mode: endpoint}: an intelligence service. world POSTs each turn to
 * {@code /turn} ({@link TurnEndpoint}); there is no socket, no turn feed, no world client and no
 * service token. Needs {@code spring-security-oauth2-jose} on the service's classpath (an optional
 * dependency of this library, so that the npc services don't get it).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "controller", name = "mode", havingValue = "endpoint")
@EnableConfigurationProperties(EndpointProperties.class)
@Import(TurnEndpoint.class)
class EndpointConfiguration {

    /** auth's issuer ({@code iss}) of every token. */
    static final String ISSUER = "scarcity-auth";

    @Bean
    Planner planner(Chooser chooser, CommonProperties commonProperties) {
        return new Planner(chooser, commonProperties.fallbackWait());
    }

    @Bean
    OutcomeCounters outcomeCounters(CommonProperties commonProperties, MeterRegistry meters) {
        return new OutcomeCounters(commonProperties.principal(), meters);
    }

    /** Verifies world's token against auth's keys: signature, expiry and issuer. */
    @Bean
    WorldCaller worldCaller(EndpointProperties endpointProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(endpointProperties.jwkSetUri().toString())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return new WorldCaller(decoder);
    }
}
