package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoomsTest {

    private static final double T = Rooms.THICKNESS;

    @Test
    void fourStoneWallsCoverTheFrameAroundTheRoom() {
        List<Entity> walls = Rooms.enclose(10.0, 8.0, T);

        assertThat(walls).hasSize(4);
        assertThat(walls).allSatisfy(wall -> {
            assertThat(wall.prototype()).isEqualTo("stone-wall");
            assertThat(wall.polygon()).hasSize(4);
        });
        for (double x = -T + 0.05; x < 10.0 + T; x += 0.1) {
            for (double y = -T + 0.05; y < 8.0 + T; y += 0.1) {
                boolean inRoom = x >= 0 && x < 10.0 && y >= 0 && y < 8.0;
                assertThat(covered(walls, x, y)).as("(%s, %s)", x, y).isEqualTo(!inRoom);
            }
        }
    }

    @Test
    void theAreasAreEnclosed() {
        assertThat(TestArea.walls()).hasSize(4);
        assertThat(Levels.walls()).hasSize(4);
        assertThat(DiveGame.walls()).hasSize(4);
        assertThat(WorldIds.WALLS).doesNotContainAnyElementsOf(WorldIds.DIVE_WALLS).hasSize(4);
    }

    @Test
    void noSeededEntityStartsInsideAWall() {
        List<Entity> starters = List.of(TestArea.pillar(), TestArea.orbiter(), TestArea.mouse(0),
                TestArea.mouse(1), TestArea.mouse(2), DiveGame.diver(), DiveGame.mouse(),
                DiveGame.stairsUp(), DiveGame.stairsDown());
        for (Entity entity : starters) {
            Position p = entity.position();
            double r = entity.radius() == null ? 0.0 : entity.radius();
            assertThat(p.x() - r).as(entity.prototype()).isGreaterThanOrEqualTo(0.0);
            assertThat(p.y() - r).isGreaterThanOrEqualTo(0.0);
            assertThat(p.x() + r).isLessThanOrEqualTo(Levels.WIDTH);
            assertThat(p.y() + r).isLessThanOrEqualTo(Levels.HEIGHT);
        }
    }

    private static boolean covered(List<Entity> walls, double x, double y) {
        return walls.stream().anyMatch(w -> {
            List<Position> p = w.polygon();
            return x >= p.get(0).x() && x <= p.get(1).x() && y >= p.get(0).y() && y <= p.get(2).y();
        });
    }
}
