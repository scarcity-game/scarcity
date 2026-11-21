package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import org.junit.jupiter.api.Test;

/** The test room's mice sit inside the room, clear of the pillar, the orbit and each other. */
class TestAreaMiceTest {

    private static final Position CENTRE = new Position(5.0, 4.0);
    private static final double ORBIT_RADIUS = 3.0;

    @Test
    void miceAreInTheRoomClearOfTheOrbitAndEachOther() {
        for (int i = 0; i < TestArea.MICE.size(); i++) {
            Entity mouse = TestArea.mouse(i);
            Position p = mouse.position();
            assertThat(mouse.prototype()).isEqualTo("mouse");
            assertThat(p.x()).isBetween(mouse.radius(), 10.0 - mouse.radius());
            assertThat(p.y()).isBetween(mouse.radius(), 8.0 - mouse.radius());
            // Beyond the orbiter's path (a circle round the pillar) by more than both radii.
            assertThat(distance(p, CENTRE)).isGreaterThan(ORBIT_RADIUS + 0.3 + mouse.radius());
            for (int j = i + 1; j < TestArea.MICE.size(); j++) {
                assertThat(distance(p, TestArea.MICE.get(j))).isGreaterThan(1.0);
            }
        }
    }

    private static double distance(Position a, Position b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
