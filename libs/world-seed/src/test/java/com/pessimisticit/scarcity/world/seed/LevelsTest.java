package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Generated dive levels (#311): what world-gen lays out, with the stairs, the mouse and the diver placed in it. */
class LevelsTest {

    private static final double DIVER = DiveGame.diver().radius();

    @Test
    void theDiverIsNoWiderThanTheCorridorsAreMadeFor() {
        assertThat(DIVER).isLessThanOrEqualTo(Levels.PASSER_RADIUS);
    }

    @Test
    void nothingStartsInsideOrTouchingAWall() {
        for (int seed = 0; seed < 200; seed++) {
            Levels.Level level = Levels.make(seed % 5, new SplittableRandom(seed));
            Entity mouse = level.mouse();
            assertClear(level, level.up(), DIVER, "the diver on the stairs up, seed " + seed);
            assertClear(level, level.down(), 0.0, "the stairs down, seed " + seed);
            assertClear(level, mouse.position(), mouse.radius(), "the mouse, seed " + seed);
            assertThat(mouse.position().distanceTo(level.up())).as("the mouse clear of the diver, seed " + seed)
                    .isGreaterThan(DIVER + mouse.radius());
            assertThat(level.walls()).allSatisfy(w -> assertThat(w.prototype()).isEqualTo("stone-wall"));
        }
    }

    @Test
    void aLevelIsAFunctionOfItsDrawsAndRecordsThemAll() {
        Levels.Level a = Levels.make(3, new SplittableRandom(7));
        Levels.Level b = Levels.make(3, new SplittableRandom(7));

        assertThat(a.walls()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b.walls());
        assertThat(a.draws()).isEqualTo(b.draws());
        assertThat(a.up()).isEqualTo(b.up());
        assertThat(a.mouseAt()).isEqualTo(b.mouseAt());
        assertThat(a.draws()).extracting(Recorder.Named::name).first().isEqualTo("rooms");
        assertThat(a.draws()).extracting(Recorder.Named::name).last().isEqualTo("mouse.speed");
        assertThat(a.draws()).extracting(Recorder.Named::name).containsSubsequence("mouse.spawn", "mouse.radius", "mouse.mass", "mouse.speed");
        assertThat(a.tessellation()).containsEntry("type", "squares");
        assertThat(a.generator()).containsEntry("type", "rooms-and-corridors");
    }

    @Test
    void theMouseIsAsDrawn() {
        for (int seed = 0; seed < 50; seed++) {
            Levels.Level level = Levels.make(0, new SplittableRandom(seed));
            Map<String, Draw> byName = new HashMap<>();
            level.draws().forEach(d -> byName.put(d.name(), d.draw()));
            Draw.Gaussian radius = (Draw.Gaussian) byName.get("mouse.radius");
            Draw.Gaussian mass = (Draw.Gaussian) byName.get("mouse.mass");
            assertThat(level.mouse().radius()).isEqualTo(radius.value()).isPositive();
            assertThat(level.mouse().mass()).isEqualTo(mass.value()).isPositive();
            Draw.Gaussian speed = (Draw.Gaussian) byName.get("mouse.speed");
            assertThat(level.mouse().speed()).isEqualTo(speed.value()).isPositive();
            assertThat(speed.mean()).isEqualTo(0.5);
            assertThat(radius.mean()).isEqualTo(0.04);
            assertThat(mass.mean()).isEqualTo(20.0);
            assertThat(radius.sd()).isCloseTo(0.002, within(1e-15));
        }
    }

    @Test
    void theHandMadeRoomsMiceStayAtThePrototypesValues() {
        for (int i = 0; i < 3; i++) {
            assertThat(TestArea.mouse(i).radius()).isEqualTo(0.04);
            assertThat(TestArea.mouse(i).mass()).isEqualTo(20.0);
            assertThat(TestArea.mouse(i).speed()).isEqualTo(0.5);
        }
    }

    private static void assertClear(Levels.Level level, Position p, double radius, String what) {
        for (Entity wall : level.walls()) {
            assertThat(distance(p, wall.polygon())).as(what).isGreaterThan(radius);
        }
    }

    /** From {@code p} to the convex, counter-clockwise polygon {@code v}: 0 inside it. */
    private static double distance(Position p, List<Position> v) {
        boolean inside = true;
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < v.size(); i++) {
            Position a = v.get(i);
            Position b = v.get((i + 1) % v.size());
            double ex = b.x() - a.x();
            double ey = b.y() - a.y();
            inside &= ex * (p.y() - a.y()) - ey * (p.x() - a.x()) >= 0.0;
            double s = Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / (ex * ex + ey * ey), 0.0, 1.0);
            nearest = Math.min(nearest, Math.hypot(p.x() - a.x() - s * ex, p.y() - a.y() - s * ey));
        }
        return inside ? 0.0 : nearest;
    }
}
