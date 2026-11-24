package com.pessimisticit.scarcity.world;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code world.build}: the {@code sha} (git commit) this server was built from, env
 * {@code WORLD_BUILD_SHA}, which the image sets from CI's {@code GIT_SHA} build arg
 * ({@code Dockerfile}). It goes into every new game's seed, so a seed from another build can be
 * told (docs/resources.md, "Games"). Absent (a local build): {@code unknown}.
 */
@Validated
@ConfigurationProperties("world.build")
record WorldBuildProperties(
        @NotNull @Pattern(regexp = "[0-9A-Za-z._-]{1,64}") @DefaultValue("unknown") String sha) {
}
