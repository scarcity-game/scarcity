package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.world.model.Position;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * orbital's configuration ({@code application.yml}, prefix {@code orbital}). The orbit,
 * the target and the projectile are orbital's, not game state: they are how this
 * controller plays.
 *
 * @param worldUrl     world's base URL
 * @param apiKey       orbital's key for world; never logged ({@link #toString()} hides it)
 * @param entity       the entity orbital plays
 * @param interval     the wait after each response
 * @param seed         the draws' seed; null: random, logged at startup
 * @param healthWindow how recent the last accepted intent must be for health UP
 * @param weights      choice name to weight
 */
@ConfigurationProperties("orbital")
public record OrbitalProperties(
        URI worldUrl,
        String apiKey,
        String entity,
        Duration interval,
        Long seed,
        Duration healthWindow,
        Map<String, Double> weights,
        StepSettings step,
        @Name("wait") WaitSettings waiting,
        @Name("throw") ThrowSettings throwing) {

    public OrbitalProperties {
        require(worldUrl, "orbital.world-url");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("orbital.api-key: required (ORBITAL_API_KEY)");
        }
        require(entity, "orbital.entity");
        positive(require(interval, "orbital.interval"), "orbital.interval");
        positive(require(healthWindow, "orbital.health-window"), "orbital.health-window");
        weights = Map.copyOf(require(weights, "orbital.weights"));
        require(step, "orbital.step");
        require(waiting, "orbital.wait");
        require(throwing, "orbital.throw");
    }

    /** Where {@code step} goes: the next point {@code arc} further round a circle. */
    public record StepSettings(Position center, double radius, double arc) {
        public StepSettings {
            require(center, "orbital.step.center");
        }
    }

    /** {@code wait}'s duration (game time). */
    public record WaitSettings(double duration) {
    }

    /** {@code throw} at the target's position; {@code projectile} is JSON (world-model's {@code Projectile}). */
    public record ThrowSettings(String target, double spread, double recovery, String projectile) {
        public ThrowSettings {
            require(target, "orbital.throw.target");
            require(projectile, "orbital.throw.projectile");
        }
    }

    @Override
    public String toString() {
        return "OrbitalProperties[worldUrl=" + worldUrl + ", apiKey=<hidden>, entity=" + entity
                + ", interval=" + interval + ", seed=" + seed + ", weights=" + weights + "]";
    }

    private static <T> T require(T value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + ": required");
        }
        return value;
    }

    private static void positive(Duration value, String key) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + ": must be positive, got " + value);
        }
    }
}
