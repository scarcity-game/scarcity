package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.GameSeed;
import com.pessimisticit.scarcity.world.model.MaterialPatterns;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.assertj.core.api.InstanceOfAssertFactories;
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
            assertClear(level, level.stairsDown().position(), 0.0, "the stairs down, seed " + seed);
            assertClear(level, mouse.position(), mouse.radius(), "the mouse, seed " + seed);
            assertThat(mouse.position().distanceTo(level.up())).as("the mouse clear of the diver, seed " + seed)
                    .isGreaterThan(DIVER + mouse.radius());
            assertThat(level.walls()).allSatisfy(w -> assertThat(w.prototype()).isEqualTo("wall"));
        }
    }

    @Test
    void aLevelIsAFunctionOfItsDrawsAndRecordsThemAll() {
        Levels.Level a = Levels.make(3, new SplittableRandom(7));
        Levels.Level b = Levels.make(3, new SplittableRandom(7));

        assertThat(a.walls()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b.walls());
        assertThat(a.draws()).isEqualTo(b.draws());
        assertThat(a.up()).isEqualTo(b.up());
        assertThat(a.mouse()).isEqualTo(b.mouse());
        assertThat(a.material()).isEqualTo(b.material());
        assertThat(a.draws()).extracting(Recorder.Named::name).startsWith("level.strategy", "level.material");
        assertThat(a.draws()).extracting(Recorder.Named::name).last().isEqualTo("stairs.down.mass");
        assertThat(a.draws()).extracting(Recorder.Named::name).containsSubsequence("mouse.spawn", "mouse.radius",
                "mouse.mass", "mouse.speed", "stairs.up.mass", "stairs.down.mass");
        assertThat(a.draws()).extracting(Recorder.Named::name).doesNotHaveDuplicates();
    }

    @Test
    void theStrategyIsDrawnAndTheLogNamesTheTessellationAndGeneratorWithTheirParameters() {
        Set<Object> seen = new HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            Levels.Level level = Levels.make(0, new SplittableRandom(seed));
            Draw.Uniform strategy = (Draw.Uniform) level.draws().getFirst().draw();
            assertThat(strategy).extracting(Draw.Uniform::min, Draw.Uniform::max).containsExactly(0.0, 2.0);
            boolean voronoi = Math.floor(strategy.value()) == 1.0;
            seen.add(level.tessellation().get("type"));
            assertThat(level.tessellation().get("type")).isEqualTo(voronoi ? "voronoi" : "squares");
            assertThat(level.generator().get("type")).isEqualTo(voronoi ? "cave" : "rooms-and-corridors");
            assertThat(level.draws().get(2).name()).isEqualTo(voronoi ? "voronoi.site[0].x" : "rooms");
            assertThat(level.tessellation().get("params")).asInstanceOf(InstanceOfAssertFactories.MAP).isNotEmpty();
            assertThat(level.generator().get("params")).asInstanceOf(InstanceOfAssertFactories.MAP).isNotEmpty();
        }
        assertThat(seen).containsExactlyInAnyOrder("squares", "voronoi");
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
    void theWallsAreOfTheDrawnMaterialAndWeighTheirAreaOfItThreeMetresTall() {
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 60; seed++) {
            Levels.Level level = Levels.make(0, new SplittableRandom(seed));
            Draw.Categorical draw = (Draw.Categorical) level.draws().get(1).draw();
            Material material = Materials.all().get(draw.value());
            assertThat(level.material()).isEqualTo(material);
            seen.add(material.name());
            for (Entity wall : level.walls()) {
                assertThat(wall.material()).isEqualTo(material.name());
                assertThat(wall.name()).isEqualTo(material.name() + " wall");
                assertThat(wall.mass()).isCloseTo(material.density() * area(wall.polygon()) * 3.0, within(1e-3));
            }
        }
        assertThat(seen).hasSizeGreaterThan(4).contains("granite");
    }

    @Test
    void theStairsMassesAreVariedAroundThePrototypes() {
        for (int seed = 0; seed < 30; seed++) {
            Levels.Level level = Levels.make(1, new SplittableRandom(seed));
            Map<String, Draw> byName = new HashMap<>();
            level.draws().forEach(d -> byName.put(d.name(), d.draw()));
            Draw.Gaussian up = (Draw.Gaussian) byName.get("stairs.up.mass");
            Draw.Gaussian down = (Draw.Gaussian) byName.get("stairs.down.mass");
            assertThat(up.mean()).isEqualTo(Prototypes.get("stairs-up").mass());
            assertThat(down.sd()).isCloseTo(0.05 * Prototypes.get("stairs-down").mass(), within(1e-6));
            assertThat(level.stairsUp("entity:00000000-0000-4000-8000-000000000001").mass()).isEqualTo(up.value());
            assertThat(level.stairsDown().mass()).isEqualTo(down.value());
            assertThat(level.stairsUp(null).position()).isEqualTo(level.up());
        }
    }

    @Test
    void theDiveGamesPatternsComeFromItsSeedAndNotFromItsFirstLevel() {
        GameSeed seed = new GameSeed(5L, "abc123");
        Map<String, String> patterns = DiveGame.game(seed).materialPatterns();
        Levels.Level level = DiveGame.level(new SplittableRandom(5));

        assertThat(patterns).containsOnlyKeys(Materials.all().stream().map(Material::name).toList())
                .isEqualTo(MaterialPatterns.draw(5L).patterns());
        assertThat(DiveGame.game(seed).seed()).isEqualTo(seed);
        assertThat(level.draws()).extracting(Recorder.Named::name).first().isEqualTo("level.strategy");
        assertThat(level.draws()).extracting(Recorder.Named::name).noneMatch(n -> n.startsWith("game."));
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

    /** The area of a counter-clockwise polygon (the shoelace formula). */
    private static double area(List<Position> v) {
        double twice = 0.0;
        for (int i = 0; i < v.size(); i++) {
            Position a = v.get(i);
            Position b = v.get((i + 1) % v.size());
            twice += a.x() * b.y() - b.x() * a.y();
        }
        return twice / 2.0;
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
