package com.pessimisticit.scarcity.subscriptions;

import java.time.Duration;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;

/**
 * UP while the LISTEN connection is open (readiness group member {@code changeListener}).
 * {@code awaitListening(ZERO)} is a non-blocking read of PostgresChangeSubscriber's flag
 * ({@code isListening()}), which the library's lifecycle wrapper doesn't expose directly.
 */
@Component("changeListener")
class ListenerHealthIndicator implements HealthIndicator {

    private final ChangeSubscriber subscriber;

    ListenerHealthIndicator(ChangeSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public Health health() {
        try {
            return subscriber.awaitListening(Duration.ZERO) ? Health.up().build()
                    : Health.down().withDetail("listening", false).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.unknown().build();
        }
    }
}
