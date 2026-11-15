package com.pessimisticit.scarcity.orbital;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health {@code world}: UP while world accepted an intent within the window, DOWN before
 * the first one and after a silence. Not in the liveness or readiness group: a world
 * outage shouldn't restart orbital.
 */
public final class WorldHealth implements HealthIndicator {

    private final Player player;
    private final Clock clock;
    private final Duration window;

    public WorldHealth(Player player, Clock clock, Duration window) {
        this.player = player;
        this.clock = clock;
        this.window = window;
    }

    @Override
    public Health health() {
        Optional<Instant> last = player.lastAccepted();
        Health.Builder health = last.filter(t -> Duration.between(t, clock.instant()).compareTo(window) < 0)
                .isPresent() ? Health.up() : Health.down();
        return health.withDetail("lastAcceptedIntent", last.map(Instant::toString).orElse("never"))
                .withDetail("window", window.toString())
                .build();
    }
}
