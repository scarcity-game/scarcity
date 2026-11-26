package com.pessimisticit.scarcity.controller;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What every controller service sets, whichever way it is told its turns (prefix {@code controller},
 * the same block as {@link ControllerProperties} and {@link EndpointProperties}): who it is, how it
 * draws its choices and how it passes a turn it can't play.
 *
 * @param principal    the service principal; also the prefix of its metrics ({@code <principal>.intents}),
 *                     observations and threads
 * @param mode         how it is told its turns; null: {@link Mode#SOCKET}
 * @param seed         the draws' seed; null: random, logged at startup
 * @param weights      choice name to weight; a choice with weight 0 is never made
 * @param fallbackWait game seconds of the {@code wait} answered when no choice yields an intent, so
 *                     the turn passes on; null: {@value ControllerProperties#DEFAULT_FALLBACK_WAIT}
 */
@Validated
@ConfigurationProperties("controller")
public record CommonProperties(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9-]*") String principal,
        Mode mode,
        Long seed,
        @NotEmpty Map<String, @NotNull @PositiveOrZero Double> weights,
        @Positive Double fallbackWait) {

    /** How the service is told its turns ({@code controller.mode}). */
    public enum Mode {
        /** It holds a WebSocket to subscriptions and posts intents to world (npc services: orbital, diver). */
        SOCKET,
        /** world POSTs it each turn, and the response is the intent (intelligence services: microbe). */
        ENDPOINT
    }

    public CommonProperties {
        mode = mode == null ? Mode.SOCKET : mode;
        weights = weights == null ? null : Map.copyOf(weights);
        fallbackWait = fallbackWait == null ? ControllerProperties.DEFAULT_FALLBACK_WAIT : fallbackWait;
    }
}
