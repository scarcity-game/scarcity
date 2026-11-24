package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VoronoiGraphTest {

    private static final double W = 24.0;
    private static final double H = 18.0;

    static VoronoiGraph graph(long seed, int sites, int relaxations) {
        return new VoronoiGraph(W, H, sites, relaxations, new Recorder(new SplittableRandom(seed)));
    }

    static LongStream seeds() {
        return LongStream.range(0, 40);
    }

    @Test
    void theSameSeedGivesTheSameCells() {
        VoronoiGraph a = graph(5, 200, 2);
        VoronoiGraph b = graph(5, 200, 2);
        assertThat(IntStream.range(0, a.size()).mapToObj(a::polygon).toList())
                .isEqualTo(IntStream.range(0, b.size()).mapToObj(b::polygon).toList());
        assertThat(graph(6, 200, 2).polygon(0)).isNotEqualTo(a.polygon(0));
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void everyCellIsAValidConvexPolygonInsideTheBox(long seed) {
        VoronoiGraph g = graph(seed, WorldGen.sites(W, H, 0.3), 3);
        for (int c = 0; c < g.size(); c++) {
            // A stone-wall refuses a polygon that isn't 3+ distinct vertices, convex and counter-clockwise.
            assertThat(Prototypes.get("stone-wall").withPolygon(g.polygon(c))).isNotNull();
            assertThat(g.polygon(c)).allSatisfy(v -> {
                assertThat(v.x()).isBetween(0.0, W);
                assertThat(v.y()).isBetween(0.0, H);
            });
        }
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void theCellsFillTheBoxAndNeighboursShareEqualVertices(long seed) {
        VoronoiGraph g = graph(seed, WorldGen.sites(W, H, 0.3), 3);
        double area = IntStream.range(0, g.size()).mapToDouble(c -> area(g.polygon(c))).sum();
        assertThat(area).isCloseTo(W * H, within(0.05));
        for (int c = 0; c < g.size(); c++) {
            Set<Position> mine = new HashSet<>(g.polygon(c));
            assertThat(g.neighbours(c)).doesNotHaveDuplicates().doesNotContain(c);
            for (int n : g.neighbours(c)) {
                // Exactly equal Positions, not merely close: CellWalls compares them with equals.
                long shared = g.polygon(n).stream().filter(mine::contains).count();
                assertThat(shared).as("cells %d and %d", c, n).isEqualTo(2);
                assertThat(g.neighbours(n)).contains(c);
            }
            // A cell with fewer neighbours than edges has an edge on the box.
            assertThat(g.onBorder(c)).isEqualTo(g.neighbours(c).size() < g.polygon(c).size());
        }
        assertThat(g.minEdge()).isGreaterThan(0.0);
    }

    @Test
    void borderCellsAreTheOnesTouchingTheBox() {
        VoronoiGraph g = graph(1, 150, 2);
        for (int c = 0; c < g.size(); c++) {
            boolean touches = g.polygon(c).stream()
                    .anyMatch(v -> v.x() == 0.0 || v.y() == 0.0 || v.x() == W || v.y() == H);
            assertThat(g.onBorder(c)).as("cell %d", c).isEqualTo(touches);
        }
    }

    @Test
    void sitesAreNamedDrawsAndTheirNumberComesFromTheCellSize() {
        Recorder recorder = new Recorder(new SplittableRandom(2));
        new VoronoiGraph(W, H, 10, 1, recorder);
        assertThat(recorder.draws()).hasSize(20);
        assertThat(recorder.draws().get(0).name()).isEqualTo("voronoi.site[0].x");
        assertThat(recorder.draws().get(19).name()).isEqualTo("voronoi.site[9].y");
        // Mean cell area W * H / sites = cellSize^2.
        assertThat(WorldGen.sites(W, H, 0.3)).isEqualTo(675);
        assertThat(WorldGen.sites(W, H, 0.6)).isEqualTo(169);
    }

    @Test
    void moreRelaxationMakesTheCellsMoreEven() {
        double previous = Double.POSITIVE_INFINITY;
        for (int rounds : new int[] {0, 1, 3, 8}) {
            double spread = 0.0;
            for (long seed = 0; seed < 5; seed++) {
                spread += spread(graph(seed, 300, rounds));
            }
            assertThat(spread).as("spread of cell areas after %d rounds", rounds).isLessThan(previous);
            previous = spread;
        }
    }

    /** The standard deviation of the cells' areas. */
    private static double spread(VoronoiGraph g) {
        double[] areas = IntStream.range(0, g.size()).mapToDouble(c -> area(g.polygon(c))).toArray();
        double mean = Arrays.stream(areas).average().orElseThrow();
        return Math.sqrt(Arrays.stream(areas).map(a -> (a - mean) * (a - mean)).average().orElseThrow());
    }

    static double area(List<Position> polygon) {
        double twice = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            twice += a.x() * b.y() - b.x() * a.y();
        }
        return twice / 2.0;
    }
}
