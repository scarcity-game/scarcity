package com.pessimisticit.scarcity.subscriptions;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;

/**
 * UP while the LISTEN connection is open (readiness group member {@code changeListener}).
 */
@Component("changeListener")
class ListenerHealthIndicator implements HealthIndicator {

    private final ChangeSubscriber changeSubscriber;

    ListenerHealthIndicator(ChangeSubscriber changeSubscriber) {
        this.changeSubscriber = changeSubscriber;
    }

    @Override
    public Health health() {
        return changeSubscriber.isListening() ? Health.up().build()
                : Health.down().withDetail("listening", false).build();
    }
}
