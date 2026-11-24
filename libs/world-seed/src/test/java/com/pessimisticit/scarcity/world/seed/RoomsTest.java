package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoomsTest {

    private static final double T = Rooms.THICKNESS;

    @Test
    void fourGraniteWallsCoverTheFrameAroundTheRoom() {
        List<Entity> walls = Rooms.enclose(10.0, 8.0, T);

        assertThat(walls).hasSize(4);
        assertThat(walls).allSatisfy(wall -> {
            assertThat(wall.prototype()).isEqualTo("wall");
            assertThat(wall.material()).isEqualTo("granite");
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
    void eachWallWeighsItsAreaOfGraniteThreeMetresTall() {
        List<Entity> walls = Rooms.enclose(10.0, 8.0, T);

        // South: 11 x 0.5 m; west: 0.5 x 9 m; 2.7 t/m3, 3 m tall.
        assertThat(walls.get(0).mass()).isCloseTo(2_700_000.0 * 11.0 * 0.5 * 3.0, within(1e-6));
        assertThat(walls.get(2).mass()).isCloseTo(2_700_000.0 * 0.5 * 9.0 * 3.0, within(1e-6));
    }

    @Test
    void theTestGameIsHandMadeSoItsPatternsAreTheFallbacks() {
        assertThat(TestArea.game().materialPatterns()).isEqualTo(Materials.fallbackPatterns());
        assertThat(TestArea.pillar().mass()).isEqualTo(Prototypes.get("pillar").mass());
    }

    @Test
    void theTestRoomIsEnclosed() {
        assertThat(TestArea.walls()).hasSize(4);
        assertThat(WorldIds.WALLS).hasSize(4).doesNotContain(WorldIds.diveWall(0));
    }

    @Test
    void noEntityOfTheTestRoomStartsInsideAWall() {
        List<Entity> starters = List.of(TestArea.pillar(), TestArea.orbiter(), TestArea.mouse(0),
                TestArea.mouse(1), TestArea.mouse(2));
        for (Entity entity : starters) {
            Position p = entity.position();
            double r = entity.radius() == null ? 0.0 : entity.radius();
            assertThat(p.x() - r).as(entity.prototype()).isGreaterThanOrEqualTo(0.0);
            assertThat(p.y() - r).isGreaterThanOrEqualTo(0.0);
            assertThat(p.x() + r).isLessThanOrEqualTo(10.0);
            assertThat(p.y() + r).isLessThanOrEqualTo(8.0);
        }
    }

    private static boolean covered(List<Entity> walls, double x, double y) {
        return walls.stream().anyMatch(w -> {
            List<Position> p = w.polygon();
            return x >= p.get(0).x() && x <= p.get(1).x() && y >= p.get(0).y() && y <= p.get(2).y();
        });
    }
}
