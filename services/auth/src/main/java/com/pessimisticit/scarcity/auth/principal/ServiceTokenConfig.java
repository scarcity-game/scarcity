package com.pessimisticit.scarcity.auth.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pessimisticit.scarcity.auth.AuthProperties;

@Configuration(proxyBeanMethods = false)
public class ServiceTokenConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenConfig.class);

    /** Read once at startup; a malformed key directory fails the start. */
    @Bean
    ServiceKeys serviceKeys(AuthProperties properties) {
        ServiceKeys keys = ServiceKeys.fromDirectory(properties.serviceToken().keysDir());
        if (keys.isEmpty()) {
            log.info("no service API keys configured: every service-token request is refused");
        } else {
            log.info("service API keys configured for principals {}", keys.principals());
        }
        return keys;
    }
}
