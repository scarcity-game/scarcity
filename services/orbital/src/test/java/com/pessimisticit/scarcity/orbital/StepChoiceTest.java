package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Position;
import org.junit.jupiter.api.Test;

class StepChoiceTest {

    private final StepChoice stepChoice;
    private final Position position;

    StepChoiceTest() {
        this.stepChoice = new StepChoice(Fixtures.ORBIT);
        this.position = Fixtures.ORBIT.position();
    }

    @Test
    void theNextPointIsArcOverRadiusFurtherRound() {
        Position next = stepChoice.next(new Position(8.0, 4.0));
        double angle = 0.5 / 3.0;
        assertThat(next.x()).isCloseTo(5.0 + 3.0 * Math.cos(angle), within(1e-12));
        assertThat(next.y()).isCloseTo(4.0 + 3.0 * Math.sin(angle), within(1e-12));
        assertThat(next.distanceTo(position)).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void anOffOrbitPositionGoesBackOntoTheRadiusAtItsOwnAngle() {
        Position inside = new Position(5.0, 5.0); // angle pi/2, distance 1
        Position next = stepChoice.next(inside);
        assertThat(next.distanceTo(position)).isCloseTo(3.0, within(1e-12));
        assertThat(Math.atan2(next.y() - 4.0, next.x() - 5.0)).isCloseTo(Math.PI / 2 + 0.5 / 3.0, within(1e-12));
    }

    @Test
    void manyStepsNeverDriftTheRadius() {
        Position at = new Position(8.0, 4.0);
        for (int i = 0; i < 10_000; i++) {
            at = stepChoice.next(at);
        }
        assertThat(at.distanceTo(position)).isCloseTo(3.0, within(1e-9));
    }
}
