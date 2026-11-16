package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackOffTest {

    @Test
    void doublesUpToTheMaxAndResets() {
        BackOff b = new BackOff(Duration.ofSeconds(1), Duration.ofSeconds(30));
        long[] seconds = new long[7];
        for (int i = 0; i < seconds.length; i++) {
            seconds[i] = b.next().toSeconds();
        }
        assertThat(seconds).containsExactly(1, 2, 4, 8, 16, 30, 30);
        b.reset();
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(1));
    }
}
