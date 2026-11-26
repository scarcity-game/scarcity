package com.pessimisticit.scarcity.controller;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of {@code controller.mode: endpoint} only (prefix {@code controller}): where to find
 * the keys that verify world's token.
 *
 * @param jwkSetUri auth's JWKS ({@code …/api/auth/.well-known/jwks.json})
 */
@Validated
@ConfigurationProperties("controller")
public record EndpointProperties(@NotNull URI jwkSetUri) {
}
