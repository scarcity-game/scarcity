package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Position;
import org.junit.jupiter.api.Test;

class StepChoiceTest {

    private final StepChoice step;
    private final Position center;

    StepChoiceTest() {
        this.step = new StepChoice(Fixtures.ORBIT);
        this.center = Fixtures.ORBIT.center();
    }

    @Test
    void theNextPointIsArcOverRadiusFurtherRound() {
        Position next = step.next(new Position(8.0, 4.0));
        double angle = 0.5 / 3.0;
        assertThat(next.x()).isCloseTo(5.0 + 3.0 * Math.cos(angle), within(1e-12));
        assertThat(next.y()).isCloseTo(4.0 + 3.0 * Math.sin(angle), within(1e-12));
        assertThat(next.distanceTo(center)).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void anOffOrbitPositionGoesBackOntoTheRadiusAtItsOwnAngle() {
        Position inside = new Position(5.0, 5.0); // angle pi/2, distance 1
        Position next = step.next(inside);
        assertThat(next.distanceTo(center)).isCloseTo(3.0, within(1e-12));
        assertThat(Math.atan2(next.y() - 4.0, next.x() - 5.0)).isCloseTo(Math.PI / 2 + 0.5 / 3.0, within(1e-12));
    }

    @Test
    void manyStepsNeverDriftTheRadius() {
        Position at = new Position(8.0, 4.0);
        for (int i = 0; i < 10_000; i++) {
            at = step.next(at);
        }
        assertThat(at.distanceTo(center)).isCloseTo(3.0, within(1e-9));
    }
}
