package com.pessimisticit.scarcity.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;

class ListenerHealthIndicatorTest {

    @Test
    void healthIsUpWhenSubscriberIsListening() {
        ChangeSubscriber subscriber = mock(ChangeSubscriber.class);
        when(subscriber.isListening()).thenReturn(true);

        ListenerHealthIndicator indicator = new ListenerHealthIndicator(subscriber);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void healthIsDownWhenSubscriberIsNotListening() {
        ChangeSubscriber subscriber = mock(ChangeSubscriber.class);
        when(subscriber.isListening()).thenReturn(false);

        ListenerHealthIndicator indicator = new ListenerHealthIndicator(subscriber);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("listening", false);
    }
}