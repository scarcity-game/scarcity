package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link Outline}: areas that aren't a box (#387), convex or not, on every tessellation and generator. */
class OutlineTest {

    private static final double DIVER = 0.3;
    private static final double W = 24.0;
    private static final double H = 18.0;
    private static final double ROUNDING = 1e-6;

    private static final List<Tessellation> TESSELLATIONS = List.of(new Tessellation.Squares(),
            Tessellation.Voronoi.DEFAULT, Tessellation.Penrose.DEFAULT);

    private static final List<Generator> GENERATORS = List.of(
            new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT), new CaveGenerator(CaveGenerator.Settings.DEFAULT));

    static Stream<Arguments> pairs() {
        return TESSELLATIONS.stream().flatMap(t -> GENERATORS.stream().flatMap(g -> LongStream.range(0, 50)
                .mapToObj(seed -> Arguments.of(t.type(), g.type(), t, g, seed))));
    }

    static Stream<Arguments> polygonPairs() {
        return Stream.of(3, 5, 6, 8).flatMap(n -> TESSELLATIONS.stream().flatMap(t -> GENERATORS.stream()
                .flatMap(g -> LongStream.range(0, 8).mapToObj(seed -> Arguments.of(n, t.type(), g.type(), t, g, seed)))));
    }

    static LongStream seeds() {
        return LongStream.range(0, 100);
    }

    private static List<Position> star(long seed) {
        return Outline.Star.DEFAULT.draw(W, H, new Recorder(new SplittableRandom(seed)));
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void aStarIsASimplePolygonInTheBoxCounterClockwise(long seed) {
        List<Position> star = star(seed);
        assertThat(star.size()).isBetween(5, 12);
        assertThat(star).allMatch(p -> p.x() >= 0 && p.x() <= W && p.y() >= 0 && p.y() <= H);
        assertThat(area(star)).isPositive();
        for (int i = 0; i < star.size(); i++) {
            for (int j = i + 2; j < star.size(); j++) {
                if (i == 0 && j == star.size() - 1) {
                    continue;
                }
                assertThat(crosses(star.get(i), star.get((i + 1) % star.size()), star.get(j),
                        star.get((j + 1) % star.size()))).as("edges %d and %d", i, j).isFalse();
            }
        }
    }

    @Test
    void mostStarsAreNotConvex() {
        // 69 of the first 100 with the default settings; the rest are convex but not boxes.
        long concave = LongStream.range(0, 100).filter(seed -> !convex(star(seed))).count();
        assertThat(concave).isGreaterThan(50);
    }

    @Test
    void aStarsDrawsAreNamedAndRecorded() {
        Recorder recorder = new Recorder(new SplittableRandom(4));
        List<Position> star = Outline.Star.DEFAULT.draw(W, H, recorder);
        List<String> names = recorder.draws().stream().map(Recorder.Named::name).toList();
        assertThat(names.getFirst()).isEqualTo("outline.vertices");
        assertThat(names).hasSize(1 + 2 * star.size())
                .contains("outline.vertex[0].angle", "outline.vertex[0].reach");
    }

    @ParameterizedTest
    @MethodSource("sides")
    void aPolygonIsRegularUpToTheBoxsAspectStandsOnASideAndTouchesEveryEdge(int n) {
        List<Position> polygon = Outline.Polygon.stretched(n, W, H);
        assertThat(polygon).hasSize(n);
        assertThat(area(polygon)).isPositive();
        assertThat(convex(polygon)).isTrue();
        assertThat(polygon.stream().mapToDouble(Position::x).min().orElseThrow()).isCloseTo(0.0, within(1e-9));
        assertThat(polygon.stream().mapToDouble(Position::x).max().orElseThrow()).isCloseTo(W, within(1e-9));
        assertThat(polygon.stream().mapToDouble(Position::y).min().orElseThrow()).isCloseTo(0.0, within(1e-9));
        assertThat(polygon.stream().mapToDouble(Position::y).max().orElseThrow()).isCloseTo(H, within(1e-9));
        assertThat(polygon.get(0).y()).as("a flat base").isCloseTo(polygon.get(n - 1).y(), within(1e-9));
        // Squashed back to its own aspect, every side is as long as the others and every vertex as far out.
        double sx = polygon.stream().mapToDouble(Position::x).max().orElseThrow() / unitWidth(n);
        double sy = H / unitHeight(n);
        double cx = W / 2;
        double cy = polygon.get(0).y() - sy * Math.sin(-Math.PI / 2 + Math.PI / n);
        for (Position p : polygon) {
            assertThat(Math.hypot((p.x() - cx) / sx, (p.y() - cy) / sy)).isCloseTo(1.0, within(1e-9));
        }
        assertThat(area(polygon) / (W * H)).isGreaterThanOrEqualTo(Outline.Polygon.DEFAULT.leastShare() - 1e-12);
    }

    static Stream<Integer> sides() {
        return Stream.of(3, 5, 6, 7, 8);
    }

    private static double unitWidth(int n) {
        return extent(n, true);
    }

    private static double unitHeight(int n) {
        return extent(n, false);
    }

    private static double extent(int n, boolean x) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double angle = -Math.PI / 2 + Math.PI / n + 2 * Math.PI * i / n;
            double v = x ? Math.cos(angle) : Math.sin(angle);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }

    @Test
    void aPolygonsSidesAreDrawnAndFourIsTheBox() {
        Set<Integer> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            Recorder recorder = new Recorder(new SplittableRandom(seed));
            List<Position> polygon = Outline.Polygon.DEFAULT.draw(W, H, recorder);
            assertThat(recorder.draws()).extracting(Recorder.Named::name).containsExactly("outline.sides");
            seen.add(polygon == null ? 4 : polygon.size());
        }
        assertThat(seen).containsExactlyInAnyOrder(3, 4, 5, 6, 7, 8);
        assertThat(Outline.Polygon.DEFAULT.leastShare()).isCloseTo(0.5, within(1e-9));
        assertThat(Outline.Polygon.DEFAULT.params()).containsEntry("minSides", 3).containsEntry("maxSides", 8);
    }

    @Test
    void containsFollowsTheEvenOddRuleOnANotchedSquare() {
        // A square with a notch cut from the top, down to y = 2.
        List<Position> notched = List.of(new Position(0, 0), new Position(4, 0), new Position(4, 4),
                new Position(3, 4), new Position(2, 2), new Position(1, 4), new Position(0, 4));
        assertThat(Outline.contains(notched, new Position(2, 1))).isTrue();
        assertThat(Outline.contains(notched, new Position(2, 3))).isFalse();
        assertThat(Outline.contains(notched, new Position(0.5, 3.5))).isTrue();
        assertThat(Outline.contains(notched, new Position(5, 1))).isFalse();
    }

    @Test
    void theBoxLeavesEveryLevelAsItWas() {
        for (long seed = 0; seed < 5; seed++) {
            Layout before = WorldGen.squares(W, H, DIVER, RoomsAndCorridors.Settings.DEFAULT, new SplittableRandom(seed));
            Layout boxed = WorldGen.make(W, H, DIVER, new Outline.Box(), new Tessellation.Squares(),
                    new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT), new SplittableRandom(seed));
            assertThat(boxed).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0} with {1}, seed {4}")
    @MethodSource("pairs")
    void aStarShapedLevelIsCrossableAndEndsInItsOuterCells(String tessellationType, String generatorType,
            Tessellation tessellation, Generator generator, long seed) {
        crossableAndEndsInItsOuterCells(Outline.Star.DEFAULT, tessellation, generator, seed);
    }

    @ParameterizedTest(name = "{0}-gon, {1} with {2}, seed {5}")
    @MethodSource("polygonPairs")
    void aPolygonLevelIsCrossableAndEndsInItsOuterCells(int n, String tessellationType, String generatorType,
            Tessellation tessellation, Generator generator, long seed) {
        crossableAndEndsInItsOuterCells(new Outline.Polygon(n, n), tessellation, generator, seed);
    }

    private static void crossableAndEndsInItsOuterCells(Outline outline, Tessellation tessellation,
            Generator generator, long seed) {
        Layout layout = WorldGen.make(W, H, DIVER, outline, tessellation, generator, new SplittableRandom(seed));
        Recorder recorder = new Recorder(new SplittableRandom(seed));
        List<Position> star = outline.draw(W, H, recorder);
        CellGraph graph = WorldGen.trim(tessellation.make(W, H, WorldGen.cellSize(DIVER), recorder), star);
        Plan plan = generator.generate(graph, recorder, DIVER + WorldGen.MARGIN);

        assertThat(layout.draws()).isEqualTo(recorder.draws());
        assertThat(layout.cells()).hasSize(graph.size());
        assertThat(layout.cells()).allMatch(c -> Outline.contains(star, VoronoiCells.centroid(c.polygon())));
        BitSet open = plan.open();
        assertThat(new Navigation(graph, open, DIVER + WorldGen.MARGIN - ROUNDING).regions())
                .anyMatch(r -> r.get(plan.up()) && r.get(plan.down()));

        Set<String> outer = Set.copyOf(layout.outer());
        Set<String> edge = layout.cells().stream().filter(c -> onEdge(c, layout.cells())).map(Cell::id)
                .collect(Collectors.toSet());
        assertThat(outer).as("the outer cells are those with an edge no cell shares").isEqualTo(edge);
        assertThat(layout.cells()).filteredOn(c -> outer.contains(c.id())).allMatch(c -> c.kind() != Cell.Kind.FLOOR);
    }

    private static boolean onEdge(Cell cell, List<Cell> cells) {
        List<Position> p = cell.polygon();
        for (int i = 0; i < p.size(); i++) {
            Position a = p.get(i);
            Position b = p.get((i + 1) % p.size());
            boolean shared = cells.stream().filter(o -> o != cell).anyMatch(o -> holds(o.polygon(), b, a));
            if (!shared) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(List<Position> polygon, Position a, Position b) {
        for (int i = 0; i < polygon.size(); i++) {
            if (polygon.get(i).equals(a) && polygon.get((i + 1) % polygon.size()).equals(b)) {
                return true;
            }
        }
        return false;
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

    private static boolean convex(List<Position> polygon) {
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            Position c = polygon.get((i + 2) % polygon.size());
            if ((b.x() - a.x()) * (c.y() - b.y()) - (b.y() - a.y()) * (c.x() - b.x()) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean crosses(Position p, Position q, Position a, Position b) {
        double d1 = cross(p, q, a);
        double d2 = cross(p, q, b);
        double d3 = cross(a, b, p);
        double d4 = cross(a, b, q);
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0);
    }

    private static double cross(Position a, Position b, Position c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }
}
