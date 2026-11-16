package com.pessimisticit.scarcity.orbital;

import java.time.Instant;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health {@code subscription}: UP while the socket is subscribed to the area, DOWN while it
 * connects or waits to reconnect. Not in the liveness or readiness group: an outage of
 * subscriptions or auth shouldn't restart orbital.
 */
@Component("subscription")
public class SubscriptionHealth implements HealthIndicator {

    private final TurnSocket turnSocket;
    private final Player player;

    public SubscriptionHealth(TurnSocket turnSocket, Player player) {
        this.turnSocket = turnSocket;
        this.player = player;
    }

    @Override
    public Health health() {
        Instant since = turnSocket.subscribedSince();
        return (since != null ? Health.up().withDetail("subscribedSince", since.toString()) : Health.down())
                .withDetail("lastAcceptedIntent", player.lastAccepted().map(Instant::toString).orElse("never"))
                .build();
    }
}
