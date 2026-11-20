package com.pessimisticit.scarcity.auth.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict JSON input: numbers and booleans are not silently turned into strings, so
 * {@code {"username":12345}} is a 400 instead of registering user "12345". Objects and
 * arrays are already rejected for String fields by Jackson.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    JsonMapperBuilderCustomizer noScalarToStringCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
