package com.pessimisticit.scarcity.diver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * How diver's choices plan ({@code application.yml}, prefix {@code diver}). Who it is and where
 * the services are is {@code controller} (libs/service-controller).
 */
@Validated
@ConfigurationProperties("diver")
public record DiverProperties(
        @NotNull @Valid @Name("step") StepSettings stepSettings,
        @NotNull @Valid @Name("wait") WaitSettings waitSettings) {

    /** {@code step}: toward the stairs down, at most this far per step. */
    public record StepSettings(@Positive double maxDistance) {
    }

    /** {@code wait}'s duration (game time). */
    public record WaitSettings(@Positive double duration) {
    }
}
