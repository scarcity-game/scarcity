package com.pessimisticit.scarcity.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * A controller service's configuration (prefix {@code controller}): who it is, where auth,
 * world and subscriptions are, and how it draws its choices. The service's own settings (how
 * each choice plans) are its own properties.
 *
 * @param principal        the service principal (the controller's {@code principal}); also the
 *                         prefix of its metrics ({@code <principal>.intents}), observations and threads
 * @param authUrl          auth's base URL, for the service token
 * @param apiKey           the principal's key for auth; never logged ({@link #toString()} hides it)
 * @param worldUrl         world's base URL
 * @param subscriptionsUrl the subscriptions WebSocket ({@code ws://…/api/subscriptions/ws})
 * @param area             the area subscribed to; blank: the turn feed, the turns of every area
 * @param think            how long the controller waits after its turn comes, before it acts
 * @param reconnect        the first wait before reconnecting the WebSocket; it doubles up to
 *                         {@code reconnectMax}
 * @param silence          how long the socket may hear nothing (subscriptions pings every
 *                         30 s) before it is dropped and reconnected
 * @param seed             the draws' seed; null: random, logged at startup
 * @param weights          choice name to weight; a choice with weight 0 is never made
 */
@Validated
@ConfigurationProperties("controller")
public record ControllerProperties(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9-]*") String principal,
        @NotNull URI authUrl,
        @NotBlank String apiKey,
        @NotNull URI worldUrl,
        @NotNull URI subscriptionsUrl,
        @Pattern(regexp = "(area:.+)?") String area,
        @NotNull @DurationMin(millis = 0) Duration think,
        @NotNull @DurationMin(millis = 1) Duration reconnect,
        @NotNull @DurationMin(millis = 1) Duration reconnectMax,
        @NotNull @DurationMin(seconds = 1) Duration silence,
        Long seed,
        @NotEmpty Map<String, @NotNull @PositiveOrZero Double> weights) {

    public ControllerProperties {
        weights = weights == null ? null : Map.copyOf(weights);
        area = area == null || area.isBlank() ? null : area;
    }

    /** No area: the controller subscribes to the turn feed (docs/websocket.md) and plays in every area. */
    public boolean feed() {
        return area == null;
    }

    @Override
    public String toString() {
        return "ControllerProperties[principal=" + principal + ", authUrl=" + authUrl + ", apiKey=<hidden>, worldUrl="
                + worldUrl + ", subscriptionsUrl=" + subscriptionsUrl + ", area=" + area + ", think=" + think
                + ", seed=" + seed + ", weights=" + weights + "]";
    }
}
