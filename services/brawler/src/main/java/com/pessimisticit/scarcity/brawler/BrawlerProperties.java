package com.pessimisticit.scarcity.brawler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * How brawler's choices plan ({@code application.yml}, prefix {@code brawler}). Who it is and
 * where the services are is {@code controller} (libs/service-controller).
 */
@Validated
@ConfigurationProperties("brawler")
public record BrawlerProperties(
        @NotNull @Valid @Name("step") StepSettings stepSettings,
        @NotNull @Valid @Name("wait") WaitSettings waitSettings) {

    /** {@code step} toward the quarry: at most this far in one step. */
    public record StepSettings(@Positive double maxDistance) {
    }

    /** {@code wait}'s duration (game time), with nobody to fight or no way to them. */
    public record WaitSettings(@Positive double duration) {
    }
}
