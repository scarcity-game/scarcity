package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a level of caves on any tessellation must be (#312): every tessellation's test extends
 * this with the graph it makes and the layout {@link WorldGen} makes on it, from a seed.
 */
abstract class CaveLevelContract {

    static final double DIVER = 0.3;
    static final double W = 24.0;
    static final double H = 18.0;
    private static final double STAIRS = 0.5;
    /** Closer to an open cell than the biggest cell is wide: every point this near is in a cell touching it. */
    /** The ring probed round an open cell's corner: 2 mm, or less where another vertex is nearer (a short Voronoi edge). */
    private static final double RING = 0.002;

    /** The tessellation, its draws taken from {@code recorder}, as the layout's maker makes it. */
    abstract CellGraph graph(Recorder recorder);

    /** The cave generator's settings the layout's maker uses. */
    abstract CaveGenerator.Settings caves();

    /** The layout {@link WorldGen} makes from {@code seed}. */
    abstract Layout layout(long seed);

    /** The name of the tessellation's first draw. */
    abstract String firstDraw();

    static LongStream seeds() {
        return LongStream.range(0, 40);
    }

    /** The graph and the cave the layout's maker made, from the same seed. */
    record Made(CellGraph graph, Plan cave) {
    }

    Made made(long seed) {
        Recorder recorder = new Recorder(new SplittableRandom(seed));
        CellGraph graph = graph(recorder);
        return new Made(graph, new CaveGenerator(caves()).generate(graph, recorder, DIVER + WorldGen.MARGIN));
    }

    @Test
    void theSameSeedGivesTheSameLayoutAndAnotherSeedAnother() {
        assertThat(layout(7)).isEqualTo(layout(7));
        assertThat(layout(7).walls()).isNotEqualTo(layout(8).walls());
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void everyOpenCellIsReachableAndBorderCellsAreRock(long seed) {
        Made made = made(seed);
        BitSet open = made.cave().open();
        assertThat(Connectivity.reachable(made.graph(), open, made.cave().up())).isEqualTo(open);
        assertThat(open.stream()).noneMatch(made.graph()::onBorder);
        assertThat(made.cave().down()).isNotEqualTo(made.cave().up());
    }

    /**
     * A disc of the diver's radius plus the margin can go from the stairs up to the stairs down:
     * over open cells whose centroid is that far from every wall, along lines between centroids
     * that stay that far (sampled every few centimetres), measured here against the walls
     * themselves and not with the generator's own {@link Navigation}.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    void aDiscCanGoFromTheStairsUpToTheStairsDown(long seed) {
        Layout layout = layout(seed);
        double need = DIVER + WorldGen.MARGIN - 1e-9;
        List<List<Position>> cells = layout.openCells();
        int up = cellAt(cells, layout.up());
        int down = cellAt(cells, layout.down());
        boolean[] seen = new boolean[cells.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(up));
        seen[up] = true;
        while (!queue.isEmpty()) {
            int a = queue.poll();
            for (int b = 0; b < cells.size(); b++) {
                if (!seen[b] && cells.get(b).stream().filter(cells.get(a)::contains).count() == 2
                        && roomAlong(layout, VoronoiCells.centroid(cells.get(a)), VoronoiCells.centroid(cells.get(b)), need)) {
                    seen[b] = true;
                    queue.add(b);
                }
            }
        }
        assertThat(seen[down]).as("a route for a disc of radius %s", need).isTrue();
    }

    private static int cellAt(List<List<Position>> cells, Position p) {
        return IntStream.range(0, cells.size()).filter(i -> WorldGenTest.inside(p, cells.get(i))).findFirst()
                .orElseThrow();
    }

    private static boolean roomAlong(Layout layout, Position from, Position to, double need) {
        for (int k = 0; k <= 20; k++) {
            Position p = new Position(from.x() + (to.x() - from.x()) * k / 20.0, from.y() + (to.y() - from.y()) * k / 20.0);
            if (layout.walls().stream().anyMatch(w -> Geometry.distance(p, w.polygon()) < need)) {
                return false;
            }
        }
        return true;
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void wallsAreValidStoneWallsAndLeaveNoGap(long seed) {
        Layout layout = layout(seed);
        for (Entity wall : layout.walls()) {
            assertThat(wall.prototype()).isEqualTo("wall");
            assertThat(WorldJson.fromJson(WorldJson.toJson(wall), Entity.class)).isEqualTo(wall);
        }
        // Every point just round a corner of an open cell is in an open cell or a wall: no hole at a vertex.
        // (A grid of points near the open space, as for squares, doesn't work here: rock behind a
        // tiny edge between two walls is near an open cell but touches it only at a point.) The ring
        // stays inside the cells that meet at the corner: under half the way to any other vertex.
        List<Position> vertices = made(seed).graph().size() == 0 ? List.of() : allVertices(made(seed).graph());
        for (Position corner : openVertices(layout)) {
            double ring = ring(corner, vertices);
            for (int k = 0; k < 16; k++) {
                Position p = new Position(corner.x() + ring * Math.cos(k * Math.PI / 8),
                        corner.y() + ring * Math.sin(k * Math.PI / 8));
                assertThat(covered(layout, p)).as("%s round %s", p, corner).isTrue();
            }
        }
        for (List<Position> cell : layout.openCells()) {
            assertThat(outsideOfEveryEdge(cell)).as("just past an open cell's edge")
                    .allSatisfy(p -> assertThat(covered(layout, p)).isTrue());
            Position middle = VoronoiCells.centroid(cell);
            assertThat(layout.walls()).noneMatch(w -> WorldGenTest.inside(middle, w.polygon()));
        }
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void everyWallIsOneCellThatTouchesTheOpenSpace(long seed) {
        Made made = made(seed);
        Layout layout = layout(seed);
        List<List<Position>> cells = IntStream.range(0, made.graph().size()).mapToObj(made.graph()::polygon).toList();
        Set<Position> openVertices = openVertices(layout);
        for (Entity wall : layout.walls()) {
            assertThat(cells).contains(wall.polygon());
            assertThat(wall.polygon()).anyMatch(openVertices::contains);
        }
        assertThat(layout.walls()).hasSizeBetween(30, 250);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void theStairsAreClearOfEveryWall(long seed) {
        Layout layout = layout(seed);
        assertThat(layout.up()).isNotEqualTo(layout.down());
        for (Entity wall : layout.walls()) {
            assertThat(Geometry.distance(layout.up(), wall.polygon())).isGreaterThan(STAIRS);
            assertThat(Geometry.distance(layout.down(), wall.polygon())).isGreaterThan(STAIRS);
        }
        assertThat(layout.spawns()).isNotEmpty().doesNotContain(layout.up(), layout.down());
        assertThat(layout.up().distanceTo(layout.down())).isGreaterThan(5.0);
    }

    @Test
    void everyDrawIsNamedAndKeepsItsDistribution() {
        List<Recorder.Named> draws = layout(3).draws();
        assertThat(draws).extracting(Recorder.Named::name).doesNotHaveDuplicates()
                .contains(firstDraw(), "cave.stairsUp")
                .anyMatch(n -> n.matches("cave\\[0\\]\\.cell\\[\\d+]\\.open"));
        assertThat(draws.getFirst().draw()).isInstanceOf(Draw.Uniform.class);
        assertThat(draws.stream().filter(d -> d.name().startsWith("cave[0].cell")).findFirst().orElseThrow().draw())
                .isInstanceOf(Draw.Bernoulli.class);
    }

    /** A point 1 cm outside the middle of each edge of the convex, counter-clockwise polygon. */
    private static List<Position> outsideOfEveryEdge(List<Position> polygon) {
        return IntStream.range(0, polygon.size()).mapToObj(i -> {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            double len = a.distanceTo(b);
            return new Position((a.x() + b.x()) / 2 + 0.01 * (b.y() - a.y()) / len,
                    (a.y() + b.y()) / 2 - 0.01 * (b.x() - a.x()) / len);
        }).toList();
    }

    private static boolean covered(Layout layout, Position p) {
        return layout.openCells().stream().anyMatch(c -> WorldGenTest.inside(p, c))
                || layout.walls().stream().anyMatch(w -> WorldGenTest.inside(p, w.polygon()));
    }

    private static List<Position> allVertices(CellGraph graph) {
        return IntStream.range(0, graph.size()).boxed().flatMap(c -> graph.polygon(c).stream()).distinct().toList();
    }

    private static double ring(Position corner, List<Position> vertices) {
        double nearest = vertices.stream().filter(v -> !v.equals(corner)).mapToDouble(corner::distanceTo)
                .min().orElse(Double.POSITIVE_INFINITY);
        return Math.min(RING, nearest / 2.5);
    }

    private static Set<Position> openVertices(Layout layout) {
        Set<Position> vertices = new HashSet<>();
        layout.openCells().forEach(vertices::addAll);
        return vertices;
    }
}
