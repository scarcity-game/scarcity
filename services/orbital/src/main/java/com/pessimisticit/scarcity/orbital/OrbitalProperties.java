package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.world.model.Position;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * How orbital's choices plan ({@code application.yml}, prefix {@code orbital}). The orbit,
 * the target and the projectile are orbital's, not game state: they are how this
 * controller plays. Who it is and where the services are is {@code controller}
 * (libs/service-controller).
 */
@Validated
@ConfigurationProperties("orbital")
public record OrbitalProperties(
        @NotNull @Valid @Name("step") StepSettings stepSettings,
        @NotNull @Valid @Name("wait") WaitSettings waitSettings,
        @NotNull @Valid @Name("throw") ThrowSettings throwSettings) {

    /** Where {@code step} goes: the next point {@code arc} further round a circle. */
    public record StepSettings(@NotNull Position position, @Positive double radius, double arc) {
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
}
