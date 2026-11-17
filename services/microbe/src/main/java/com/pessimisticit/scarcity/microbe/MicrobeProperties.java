package com.pessimisticit.scarcity.microbe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * How microbe's choices plan ({@code application.yml}, prefix {@code microbe}). Who it is and
 * where the services are is {@code controller} (libs/service-controller).
 */
@Validated
@ConfigurationProperties("microbe")
public record MicrobeProperties(
        @NotNull @Valid @Name("step") StepSettings stepSettings,
        @NotNull @Valid @Name("wait") WaitSettings waitSettings) {

    /** {@code step}: this far, in a uniformly drawn direction. */
    public record StepSettings(@Positive double distance) {
    }

    /** {@code wait}'s duration (game time). */
    public record WaitSettings(@Positive double duration) {
    }
}
