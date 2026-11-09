package com.pessimisticit.scarcity.auth.web;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

/**
 * Strict JSON input: numbers and booleans are not silently turned into strings, so
 * {@code {"username":12345}} is a 400 instead of registering user "12345". Objects and
 * arrays are already rejected for String fields by Jackson.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer noScalarToStringCoercion() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
