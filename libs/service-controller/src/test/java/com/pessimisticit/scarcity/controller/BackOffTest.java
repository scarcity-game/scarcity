package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackOffTest {

    @Test
    void doublesUpToTheMaxAndResets() {
        BackOff b = new BackOff(Duration.ofSeconds(1), Duration.ofSeconds(30), () -> 0.999_999_999_9);
        long[] seconds = new long[7];
        for (int i = 0; i < seconds.length; i++) {
            seconds[i] = Math.round(b.next().toMillis() / 1000.0);
        }
        assertThat(seconds).containsExactly(1, 2, 4, 8, 16, 30, 30);
        b.reset();
        assertThat(Math.round(b.next().toMillis() / 1000.0)).isEqualTo(1);
    }

    @Test
    void eachWaitIsJitteredIntoItsUpperHalf() {
        BackOff low = new BackOff(Duration.ofSeconds(4), Duration.ofSeconds(30), () -> 0.0);
        assertThat(low.next()).isEqualTo(Duration.ofSeconds(2));
        assertThat(low.next()).isEqualTo(Duration.ofSeconds(4));
        BackOff mid = new BackOff(Duration.ofSeconds(4), Duration.ofSeconds(30), () -> 0.5);
        assertThat(mid.next()).isEqualTo(Duration.ofSeconds(3));
        BackOff real = new BackOff(Duration.ofSeconds(4), Duration.ofSeconds(30));
        assertThat(real.next()).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }
}
