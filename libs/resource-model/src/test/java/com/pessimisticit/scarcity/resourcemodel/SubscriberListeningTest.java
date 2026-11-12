package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests the listening state of {@link ChangeSubscriber} implementations.
 */
class SubscriberListeningTest extends AbstractDbTest {

    @Test
    void isListeningIsTrueOnTheWiredSubscriber() throws Exception {
        assertThat(subscriber.awaitListening(Duration.ofSeconds(15))).isTrue();
        assertThat(subscriber.isListening()).isTrue();
    }

    @Test
    void isListeningFollowsStartAndStop() throws Exception {
        ChangeSubscriber s = new PostgresChangeSubscriber(() -> DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(s.isListening()).isFalse();
        s.start();
        try {
            assertThat(s.awaitListening(Duration.ofSeconds(15))).isTrue();
            assertThat(s.isListening()).isTrue();
        } finally {
            s.stop();
        }
        assertThat(s.isListening()).isFalse();
    }
}
