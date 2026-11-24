package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.BitSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorldGenTest {

    /** The diver's radius: the largest entity that must pass. */
    private static final double DIVER = 0.3;
    private static final double STAIRS = 0.5;
    /** Less than a cell: every point this close to an open cell is in a cell that touches it. */
    private static final double NEAR = 0.75;

    private static Layout layout(long seed) {
        return WorldGen.squares(24.0, 18.0, DIVER, RoomsAndCorridors.Settings.DEFAULT, new SplittableRandom(seed));
    }

    static LongStream seeds() {
        return LongStream.range(0, 40);
    }

    @Test
    void theCellSizeComesFromTheLargestPasser() {
        assertThat(WorldGen.cellSize(DIVER)).isEqualTo(0.8);
        assertThat(WorldGen.cellSize(0.45)).isCloseTo(1.2, within(1e-12));
        assertThat(WorldGen.cellSize(0.01)).isEqualTo(WorldGen.MIN_CELL);
    }

    @Test
    void theSameSeedGivesTheSameLayoutAndAnotherSeedAnother() {
        assertThat(layout(7)).isEqualTo(layout(7));
        assertThat(layout(7).walls()).isNotEqualTo(layout(8).walls());
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void everyOpenCellIsReachableFromTheFirstRoom(long seed) {
        SquareGrid grid = new SquareGrid(30, 22, 0.8);
        RoomsAndCorridors.Plan plan = new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT)
                .generate(grid, new Recorder(new SplittableRandom(seed)));
        BitSet open = plan.open();
        assertThat(Connectivity.reachable(grid, open, plan.rooms().getFirst().centre())).isEqualTo(open);
        assertThat(open.stream()).noneMatch(grid::onBorder);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void wallsAreValidStoneWallsAndLeaveNoGap(long seed) {
        Layout layout = layout(seed);
        for (Entity wall : layout.walls()) {
            assertThat(wall.prototype()).isEqualTo("stone-wall");
            assertThat(WorldJson.fromJson(WorldJson.toJson(wall), Entity.class)).isEqualTo(wall);
        }
        // Every point near the open space is in an open cell or inside a wall: the walls enclose it.
        for (double x = 0.05; x < 24.0; x += 0.1) {
            for (double y = 0.05; y < 17.6; y += 0.1) {
                Position p = new Position(x, y);
                if (layout.openCells().stream().anyMatch(c -> inside(p, c))
                        || layout.openCells().stream().noneMatch(c -> distance(p, c) < NEAR)) {
                    continue;
                }
                assertThat(layout.walls()).as("%s is covered", p).anyMatch(w -> inside(p, w.polygon()));
            }
        }
        // And no open cell is under a wall.
        for (List<Position> cell : layout.openCells()) {
            Position middle = centre(cell);
            assertThat(layout.walls()).noneMatch(w -> inside(middle, w.polygon()));
        }
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void theStairsAreClearOfEveryWall(long seed) {
        Layout layout = layout(seed);
        assertThat(layout.up()).isNotEqualTo(layout.down());
        for (Entity wall : layout.walls()) {
            assertThat(distance(layout.up(), wall.polygon())).isGreaterThan(STAIRS);
            assertThat(distance(layout.down(), wall.polygon())).isGreaterThan(STAIRS);
        }
        assertThat(layout.spawns()).isNotEmpty().doesNotContain(layout.up(), layout.down());
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void everyWallIsOneCellThatTouchesTheOpenSpace(long seed) {
        Layout layout = layout(seed);
        double cell = WorldGen.cellSize(DIVER);
        for (Entity wall : layout.walls()) {
            // A wall is no bigger than a cell, so its shape doesn't tell how far the rock goes.
            assertThat(wall.polygon()).hasSize(4);
            assertThat(wall.polygon().get(2).x() - wall.polygon().get(0).x()).isCloseTo(cell, within(1e-9));
            assertThat(wall.polygon().get(2).y() - wall.polygon().get(0).y()).isCloseTo(cell, within(1e-9));
            assertThat(layout.openCells()).anyMatch(c -> c.stream().anyMatch(wall.polygon()::contains));
        }
        // #186: a level is one batch when it is made, a reload for subscribers; keep it bounded.
        assertThat(layout.walls()).hasSizeBetween(10, 400);
    }

    @Test
    void everyDrawIsNamedAndKeepsItsDistribution() {
        List<Recorder.Named> draws = layout(3).draws();
        assertThat(draws.getFirst().name()).isEqualTo("rooms");
        assertThat(draws.getFirst().draw()).isEqualTo(new Draw.Uniform(0.0, 5.0,
                ((Draw.Uniform) draws.getFirst().draw()).value()));
        assertThat(draws).extracting(Recorder.Named::name).doesNotHaveDuplicates()
                .anyMatch(n -> n.startsWith("room[0].try[0].width"))
                .anyMatch(n -> n.startsWith("corridor[1].bendFirstAlongX"));
        assertThat(WorldJson.toJson(draws.getFirst().draw())).contains("\"dist\":\"uniform\"");
    }

    /** Whether {@code p} is in the closed convex, counter-clockwise polygon. */
    static boolean inside(Position p, List<Position> polygon) {
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            if ((b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x()) < 0.0) {
                return false;
            }
        }
        return true;
    }

    private static Position centre(List<Position> polygon) {
        double x = 0.0;
        double y = 0.0;
        for (Position v : polygon) {
            x += v.x();
            y += v.y();
        }
        return new Position(x / polygon.size(), y / polygon.size());
    }

    /** The distance from {@code p} to an axis-aligned rectangle; 0 inside it. */
    private static double distance(Position p, List<Position> rectangle) {
        double x0 = rectangle.stream().mapToDouble(Position::x).min().orElseThrow();
        double x1 = rectangle.stream().mapToDouble(Position::x).max().orElseThrow();
        double y0 = rectangle.stream().mapToDouble(Position::y).min().orElseThrow();
        double y1 = rectangle.stream().mapToDouble(Position::y).max().orElseThrow();
        double dx = Math.max(Math.max(x0 - p.x(), 0.0), p.x() - x1);
        double dy = Math.max(Math.max(y0 - p.y(), 0.0), p.y() - y1);
        return Math.hypot(dx, dy);
    }
}
