package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.world.model.Position;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * orbital's configuration ({@code application.yml}, prefix {@code orbital}). The orbit,
 * the target and the projectile are orbital's, not game state: they are how this
 * controller plays.
 *
 * @param authUrl          auth's base URL, for the service token
 * @param apiKey           orbital's key for auth; never logged ({@link #toString()} hides it)
 * @param worldUrl         world's base URL
 * @param subscriptionsUrl the subscriptions WebSocket ({@code ws://…/api/subscriptions/ws})
 * @param area             the area orbital subscribes to
 * @param think            how long orbital waits after its turn comes, before it acts
 * @param reconnect        the first wait before reconnecting the WebSocket; it doubles up to
 *                         {@code reconnectMax}
 * @param silence          how long the socket may hear nothing (subscriptions pings every
 *                         30 s) before orbital drops it and reconnects
 * @param seed             the draws' seed; null: random, logged at startup
 * @param weights          choice name to weight; a choice with weight 0 is never made
 */
@Validated
@ConfigurationProperties("orbital")
public record OrbitalProperties(
        @NotNull URI authUrl,
        @NotBlank String apiKey,
        @NotNull URI worldUrl,
        @NotNull URI subscriptionsUrl,
        @NotNull @Pattern(regexp = "area:.+") String area,
        @NotNull @DurationMin(millis = 0) Duration think,
        @NotNull @DurationMin(millis = 1) Duration reconnect,
        @NotNull @DurationMin(millis = 1) Duration reconnectMax,
        @NotNull @DurationMin(seconds = 1) Duration silence,
        Long seed,
        @NotEmpty Map<String, @NotNull @PositiveOrZero Double> weights,
        @NotNull @Valid StepSettings step,
        @NotNull @Valid @Name("wait") WaitSettings waiting,
        @NotNull @Valid @Name("throw") ThrowSettings throwing) {

    public OrbitalProperties {
        weights = weights == null ? null : Map.copyOf(weights);
    }

    /** Where {@code step} goes: the next point {@code arc} further round a circle. */
    public record StepSettings(@NotNull Position center, @Positive double radius, double arc) {
    }

    /** {@code wait}'s duration (game time). */
    public record WaitSettings(@Positive double duration) {
    }

    /** {@code throw} at the target's position; {@code projectile} is JSON (world-model's {@code Projectile}). */
    public record ThrowSettings(
            @NotNull @Pattern(regexp = "entity:.+") String target,
            @DecimalMin("0.0") double spread,
            @Positive double recovery,
            @NotBlank String projectile) {
    }

    @Override
    public String toString() {
        return "OrbitalProperties[authUrl=" + authUrl + ", apiKey=<hidden>, worldUrl=" + worldUrl
                + ", subscriptionsUrl=" + subscriptionsUrl + ", area=" + area + ", think=" + think
                + ", seed=" + seed + ", weights=" + weights + "]";
    }
}
