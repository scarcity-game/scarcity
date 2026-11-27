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

    @Test
    void aRegularPolygonsWallsRingItWithNoGap() {
        int sides = 12;
        double radius = 20.0;
        Position centre = new Position(21.0, 21.0);
        List<Entity> walls = Rooms.regular(sides, radius, centre, T);

        assertThat(walls).hasSize(sides).allSatisfy(wall -> {
            assertThat(wall.material()).isEqualTo("granite");
            assertThat(wall.polygon()).hasSize(4);
        });
        // The bottom side lies flat, at the inradius below the centre.
        double inradius = radius * Math.cos(Math.PI / sides);
        assertThat(walls.get(0).polygon().get(0).y()).isCloseTo(centre.y() - inradius, within(1e-9));
        assertThat(walls.get(0).polygon().get(3).y()).isCloseTo(centre.y() - inradius, within(1e-9));
        for (double a = 0.0; a < 2 * Math.PI; a += 0.01) {
            // Each wall's band runs from the inradius to the inradius plus the thickness, along its side's normal.
            double side = Math.PI / sides;
            double off = Math.abs(Math.IEEEremainder(a + Math.PI / 2, 2 * side));
            double in = inradius / Math.cos(off);
            assertThat(covered(walls, at(centre, in - 0.01, a))).as("inside at %s", a).isFalse();
            assertThat(covered(walls, at(centre, in + T / 2 / Math.cos(off), a))).as("wall at %s", a).isTrue();
            assertThat(covered(walls, at(centre, in + (T + 0.01) / Math.cos(off), a))).as("outside at %s", a)
                    .isFalse();
        }
    }

    private static Position at(Position centre, double r, double a) {
        return new Position(centre.x() + r * Math.cos(a), centre.y() + r * Math.sin(a));
    }

    /** Whether a convex counter-clockwise wall holds {@code p}. */
    private static boolean covered(List<Entity> walls, Position p) {
        return walls.stream().anyMatch(w -> {
            List<Position> v = w.polygon();
            for (int i = 0; i < v.size(); i++) {
                Position a = v.get(i);
                Position b = v.get((i + 1) % v.size());
                if ((b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x()) < 0) {
                    return false;
                }
            }
            return true;
        });
    }

    private static boolean covered(List<Entity> walls, double x, double y) {
        return walls.stream().anyMatch(w -> {
            List<Position> p = w.polygon();
            return x >= p.get(0).x() && x <= p.get(1).x() && y >= p.get(0).y() && y <= p.get(2).y();
        });
    }
}
