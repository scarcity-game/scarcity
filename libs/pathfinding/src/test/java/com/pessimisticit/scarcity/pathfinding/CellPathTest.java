package com.pessimisticit.scarcity.pathfinding;

import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.AREA;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.DIVER;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.assertEveryLegIsClear;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.diverAt;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.pillar;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.room;
import static com.pessimisticit.scarcity.pathfinding.PathfinderTest.wall;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.gen.Layout;
import com.pessimisticit.scarcity.world.gen.RoomsAndCorridors;
import com.pessimisticit.scarcity.world.gen.WorldGen;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A* over floor cells (#355): generated levels, passages a grid would get wrong, the budget, other movers. */
class CellPathTest {

    private static final double RADIUS = 0.3;

    /** A generated level's walls (naming their cells) and cells, with the diver at the stairs up. */
    static AreaView level(Layout layout, Entity... others) {
        SplittableRandom ids = new SplittableRandom(1);
        Map<String, Entity> entities = new HashMap<>();
        for (Entity w : layout.cellWalls(Materials.get("granite"))) {
            entities.put(Ids.newId(ResourceKind.ENTITY, ids), w);
        }
        for (Entity o : others) {
            entities.put(Ids.newId(ResourceKind.ENTITY, ids), o);
        }
        entities.put(DIVER, diverAt(layout.up().x(), layout.up().y()));
        Map<String, Cell> cells = new HashMap<>();
        layout.cells().forEach(c -> cells.put(c.id(), c));
        return AreaView.of(AREA, new Area("level", 0.0), entities, cells);
    }

    static Layout voronoi(double width, double height, long seed) {
        return WorldGen.voronoi(width, height, RADIUS, WorldGen.VoronoiSettings.DEFAULT, new SplittableRandom(seed));
    }

    static Layout squares(long seed) {
        return WorldGen.squares(24.0, 18.0, RADIUS, RoomsAndCorridors.Settings.DEFAULT, new SplittableRandom(seed));
    }

    static LongStream seeds() {
        return LongStream.range(0, 20);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void onEveryGeneratedLevelTheDiverReachesTheStairsDownByLegsItCanTake(long seed) {
        for (Layout layout : List.of(voronoi(24.0, 18.0, seed), squares(seed))) {
            AreaView view = level(layout);
            Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, layout.down(), Pathfinder.Settings.DEFAULT);
            List<Position> path = plan.path().orElseThrow(() -> new AssertionError("no path, seed " + seed));
            assertThat(path.getLast()).isEqualTo(layout.down());
            assertEveryLegIsClear(view, path);
            assertThat(plan.way()).isIn(Pathfinder.Way.CELLS, Pathfinder.Way.STRAIGHT);
        }
    }

    @Test
    void theStuckDiversLevelOf345IsCrossedOverCells() {
        // A squares level like area:cb43883c… (24 x 18 m, rooms and corridors, 205 walls; with the
        // diver, mouse and stairs, 209 entities), where the diver planned through walls it hadn't
        // read: here it has them all, and their cells.
        Layout layout = squares(64);
        AreaView view = level(layout);
        assertThat(view.entities()).as("205 walls and the diver").hasSize(206);

        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, layout.down(), Pathfinder.Settings.DEFAULT);

        assertThat(plan.way()).isEqualTo(Pathfinder.Way.CELLS);
        assertThat(plan.mended()).as("the funnel's legs are clear as they are").isZero();
        assertEveryLegIsClear(view, plan.path().orElseThrow());
    }

    @Test
    void theOldRoomsArePlannedOverCellsWithNoLegMended() {
        Position to = new Position(8.0, 6.0);
        for (AreaView view : List.of(room(diverAt(2.0, 6.0), wall(4.8, 1.5, 5.2, 8.0)),
                room(diverAt(6.0, 4.5), wall(3.0, 5.0, 7.0, 5.5), wall(6.5, 1.5, 7.0, 5.5)),
                room(diverAt(4.8 - RADIUS, 6.0), wall(4.8, 1.5, 5.2, 8.0)))) {
            Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, to, Pathfinder.Settings.DEFAULT);
            assertThat(plan.way()).isEqualTo(Pathfinder.Way.CELLS);
            assertThat(plan.mended()).isZero();
        }
    }

    @Test
    void anAreaWithNoCellsIsStillPlannedRoundItsWalls() {
        AreaView view = PathfinderTest.bare(diverAt(2.0, 6.0), wall(4.8, 1.5, 5.2, 8.0));
        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, new Position(8.0, 6.0), Pathfinder.Settings.DEFAULT);
        assertThat(plan.way()).isEqualTo(Pathfinder.Way.NO_CELLS);
        assertEveryLegIsClear(view, plan.path().orElseThrow());
    }

    @Test
    void aNarrowPassageAGridOverlayWouldLoseIsTaken() {
        // A gap 0.7 m high, y 3.15 to 3.85, in a wall at x 5. On a 0.5 m grid of nodes no node in
        // the gap is 0.3 m from both sides, so a grid finds no way; its cell's clearance is 0.35.
        AreaView view = room(diverAt(2.0, 3.5), wall(4.8, 0.0, 5.2, 3.15), wall(4.8, 3.85, 5.2, 8.0));
        Position to = new Position(8.0, 3.5);

        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, to, Pathfinder.Settings.DEFAULT);

        assertThat(plan.path()).contains(List.of(to));
        AreaView off = room(diverAt(2.0, 6.5), wall(4.8, 0.0, 5.2, 3.15), wall(4.8, 3.85, 5.2, 8.0));
        Pathfinder.Plan through = Pathfinder.plan(off, DIVER, new Position(8.0, 6.5), Pathfinder.Settings.DEFAULT);
        assertThat(through.way()).isEqualTo(Pathfinder.Way.CELLS);
        assertEveryLegIsClear(off, through.path().orElseThrow());
    }

    @Test
    void aPinchPointAGridWouldPassIsBlocked() {
        // Two slats 0.1 m thick at x 5 leave a gap of 0.55 m: under the diver's 0.6. The 0.5 m grid
        // nodes either side, (4.75, 4.25) and (5.25, 4.25), are both over 0.3 m from every wall,
        // so a grid would link them; the edge between the cells is 0.55 wide.
        AreaView view = room(diverAt(2.0, 4.25), wall(4.95, 0.0, 5.05, 4.0), wall(4.95, 4.55, 5.05, 8.0));

        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, new Position(8.0, 4.25), Pathfinder.Settings.DEFAULT);

        assertThat(plan.way()).isEqualTo(Pathfinder.Way.CELLS);
        assertThat(plan.path()).isEmpty();
    }

    @Test
    void whenTheBudgetRunsOutThereIsNoPath() {
        Layout layout = voronoi(24.0, 18.0, 3);
        AreaView view = level(layout);
        Pathfinder.Settings few = new Pathfinder.Settings(10, 3, 2.0);

        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, layout.down(), few);

        assertThat(plan.way()).isEqualTo(Pathfinder.Way.CELLS);
        assertThat(plan.path()).isEmpty();
        assertThat(plan.expanded()).isEqualTo(10);
        assertThat(Pathfinder.plan(view, DIVER, layout.down(), Pathfinder.Settings.DEFAULT).path()).isPresent();
    }

    @Test
    void anotherMoverNearInTheWayIsGoneAround() {
        // A pillar in the gap's mouth, 1.5 m ahead: its cells are left out, so the way is round it.
        AreaView view = room(diverAt(2.0, 4.0), pillar(3.5, 4.0), wall(4.8, 1.5, 5.2, 8.0));
        Position to = new Position(8.0, 4.0);

        Pathfinder.Plan plan = Pathfinder.plan(view, DIVER, to, Pathfinder.Settings.DEFAULT);

        assertThat(plan.way()).isEqualTo(Pathfinder.Way.CELLS);
        assertEveryLegIsClear(view, plan.path().orElseThrow());
    }

    @Test
    void aLargeVoronoiLevelIsPlannedInBoundedTimeAndMemory() {
        // 48 x 36 m: four times a live level's area, 2,700 cells. Bigger levels take world-gen
        // minutes to make (its passages check is superlinear), not the search.
        Layout layout = voronoi(48.0, 36.0, 11);
        AreaView view = level(layout);
        Pathfinder.plan(view, DIVER, layout.down(), Pathfinder.Settings.DEFAULT);

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long bytes = threads.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        int runs = 5;
        Pathfinder.Plan plan = null;
        for (int i = 0; i < runs; i++) {
            plan = Pathfinder.plan(view, DIVER, layout.down(), Pathfinder.Settings.DEFAULT);
        }
        double ms = (System.nanoTime() - start) / 1e6 / runs;
        double mb = (threads.getCurrentThreadAllocatedBytes() - bytes) / 1e6 / runs;
        System.out.printf("large voronoi: %d cells, %d entities, %.1f ms and %.1f MB allocated per plan, "
                + "%d cells expanded, %d legs mended, %s%n", view.cells().size(), view.entities().size(), ms, mb,
                plan.expanded(), plan.mended(), plan.path().map(p -> p.size() + " waypoints").orElse("no path"));

        assertThat(ms).isLessThan(2000.0);
        if (plan.path().isPresent()) {
            assertEveryLegIsClear(view, plan.path().get());
        }
    }
}
