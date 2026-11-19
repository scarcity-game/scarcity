package com.pessimisticit.scarcity.diver;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.controller.TurnFrame;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArrivalPauseTest {

    private static final Duration PAUSE = Duration.ofSeconds(30);
    private static final String DIVER = "entity:diver";

    private final ArrivalPause pause = new ArrivalPause(new DiverProperties(
            PAUSE, new DiverProperties.StepSettings(1.0), new DiverProperties.WaitSettings(1.0)));

    private static TurnFrame frame(String area, long version) {
        return new TurnFrame(area, DIVER, version, 0.0);
    }

    @Test
    void pausesOnTheFirstTurnInAnArea() {
        assertThat(pause.before(frame("area:1", 1))).isEqualTo(PAUSE);
    }

    @Test
    void doesNotPauseOnLaterTurnsInTheSameArea() {
        pause.before(frame("area:1", 1));
        assertThat(pause.before(frame("area:1", 2))).isEqualTo(Duration.ZERO);
        assertThat(pause.before(frame("area:1", 3))).isEqualTo(Duration.ZERO);
    }

    @Test
    void pausesAgainInANewArea() {
        pause.before(frame("area:1", 1));
        pause.before(frame("area:1", 2));
        assertThat(pause.before(frame("area:2", 1))).isEqualTo(PAUSE);
        assertThat(pause.before(frame("area:2", 2))).isEqualTo(Duration.ZERO);
    }
}
