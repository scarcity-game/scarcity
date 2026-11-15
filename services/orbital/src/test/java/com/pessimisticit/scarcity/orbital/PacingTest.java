package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PacingTest {

    private final Pacing pacing = new Pacing(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @Test
    void playedTurnsWaitTheInterval() {
        for (Outcome o : new Outcome[] {Outcome.OK, Outcome.BUSY, Outcome.STALE, Outcome.INVALID,
                Outcome.UNAVAILABLE}) {
            assertThat(pacing.after(o)).as(o.tag()).isEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void anUnreachableWorldBacksOffExponentiallyToThirtySeconds() {
        assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(2));
        assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(4));
        assertThat(pacing.after(Outcome.ERROR)).isEqualTo(Duration.ofSeconds(8));
        assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(16));
        assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(30));
        for (int i = 0; i < 100; i++) {
            assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(30));
        }
        assertThat(pacing.after(Outcome.OK)).isEqualTo(Duration.ofSeconds(1));
        assertThat(pacing.after(Outcome.UNREACHABLE)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void deniedAndNotFoundWaitThirtySeconds() {
        assertThat(pacing.after(Outcome.DENIED)).isEqualTo(Duration.ofSeconds(30));
        assertThat(pacing.after(Outcome.NOT_FOUND)).isEqualTo(Duration.ofSeconds(30));
    }
}
