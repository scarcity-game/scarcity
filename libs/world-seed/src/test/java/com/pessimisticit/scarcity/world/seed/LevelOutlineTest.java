package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A level's outline (#387): a regular polygon stretched to its box (four sides: the box) or a star in it, and the bedrock walls on its edge. */
class LevelOutlineTest {

    private static final LevelShape.Ranges SMALL = new LevelShape.Ranges(20.0, 30.0, 15.0, 22.0, 1.0, 1.3);

    static LongStream seeds() {
        return LongStream.range(0, 60);
    }

    private static Levels.Level level(long seed) {
        return Levels.make(0, new SplittableRandom(seed), SMALL);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void theWallsOnTheEdgeAreBedrockAndTheRestTheLevelsMaterial(long seed) {
        Levels.Level level = level(seed);
        Set<List<Position>> edge = edgePolygons(level.cells());
        assertThat(level.walls()).isNotEmpty();
        for (Entity wall : level.walls()) {
            String expected = edge.contains(wall.polygon()) ? Materials.BEDROCK : level.material().name();
            assertThat(wall.material()).as("wall at %s", wall.position()).isEqualTo(expected);
        }
        assertThat(level.material().name()).isNotEqualTo(Materials.BEDROCK);
    }

    @Test
    void mostLevelsShowTheirEdge() {
        // A level whose open cells all keep off its edge has no bedrock wall; most reach it.
        long showing = seeds().filter(seed -> level(seed).walls().stream()
                .anyMatch(w -> Materials.BEDROCK.equals(w.material()))).count();
        assertThat(showing).isGreaterThan(seeds().count() / 2);
    }

    @Test
    void levelsComeInPolygonsAndStarsAndTheLogSaysWhich() {
        Map<Object, Integer> seen = new HashMap<>();
        for (long seed = 0; seed < 60; seed++) {
            Levels.Level level = level(seed);
            Object type = level.outline().get("type");
            seen.merge(type, 1, Integer::sum);
            List<String> names = level.draws().stream().map(Recorder.Named::name).toList();
            assertThat(names).contains("level.outline");
            assertThat(names.contains("outline.vertices")).isEqualTo("star".equals(type));
            assertThat(names.contains("outline.sides")).isEqualTo("polygon".equals(type));
        }
        assertThat(seen.keySet()).containsExactlyInAnyOrder("polygon", "star");
    }

    @Test
    void aTriangleLevelIsAboutHalfItsBox() {
        for (long seed = 0; seed < 200; seed++) {
            Levels.Level level = level(seed);
            boolean triangle = level.draws().stream().anyMatch(d -> d.name().equals("outline.sides")
                    && ((com.pessimisticit.scarcity.world.model.Draw.Uniform) d.draw()).value() < 1.0);
            if (triangle) {
                double area = level.cells().stream().mapToDouble(c -> area(c.polygon())).sum();
                assertThat(area / (level.width() * level.height())).isBetween(0.35, 0.65);
                return;
            }
        }
        throw new AssertionError("no triangle level in 200 seeds");
    }

    @Test
    void aStarLevelIsNotItsBox() {
        for (long seed = 0; seed < 60; seed++) {
            Levels.Level level = level(seed);
            if ("star".equals(level.outline().get("type"))) {
                double area = level.cells().stream().mapToDouble(c -> area(c.polygon())).sum();
                assertThat(area).isLessThan(0.8 * level.width() * level.height());
                return;
            }
        }
        throw new AssertionError("no star level in 60 seeds");
    }

    /** The polygons of cells with an edge no other cell shares. */
    private static Set<List<Position>> edgePolygons(List<Cell> cells) {
        Map<List<Position>, Integer> holders = new HashMap<>();
        for (Cell cell : cells) {
            for (List<Position> e : edges(cell.polygon())) {
                holders.merge(e, 1, Integer::sum);
            }
        }
        Set<List<Position>> edge = new HashSet<>();
        for (Cell cell : cells) {
            if (edges(cell.polygon()).stream().anyMatch(e -> holders.get(e) == 1)) {
                edge.add(cell.polygon());
            }
        }
        return edge;
    }

    /** Each edge with its ends in a fixed order, so both cells holding it give the same key. */
    private static List<List<Position>> edges(List<Position> polygon) {
        List<List<Position>> edges = new java.util.ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            boolean aFirst = a.x() < b.x() || a.x() == b.x() && a.y() < b.y();
            edges.add(aFirst ? List.of(a, b) : List.of(b, a));
        }
        return edges;
    }

    private static double area(List<Position> polygon) {
        double twice = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            twice += a.x() * b.y() - b.x() * a.y();
        }
        return twice / 2;
    }
}
