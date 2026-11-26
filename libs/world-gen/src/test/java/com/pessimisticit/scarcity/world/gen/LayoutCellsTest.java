package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The cells a {@link Layout} emits, and the walls made from them (#352), for every tiling in {@link #TILINGS}. */
class LayoutCellsTest {

    private static final double DIVER = 0.3;
    private static final double W = 24.0;
    private static final double H = 18.0;
    /** How far a border vertex may sit inside the bounds: none, a vertex on a side has that side's coordinate. */
    private static final double EPS = 0.0;

    /** A tessellation's level maker, named for the test report. */
    record Tiling(String name, LongFunction<Layout> make) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** One line per tiling: a new tiling is added here and runs every test below. */
    static final List<Tiling> TILINGS = List.of(
            new Tiling("squares", seed -> WorldGen.squares(W, H, DIVER, RoomsAndCorridors.Settings.DEFAULT,
                    new SplittableRandom(seed))),
            new Tiling("voronoi", seed -> WorldGen.voronoi(W, H, DIVER, WorldGen.VoronoiSettings.DEFAULT,
                    new SplittableRandom(seed))),
            new Tiling("penrose", seed -> WorldGen.penrose(W, H, DIVER, WorldGen.PenroseSettings.DEFAULT,
                    new SplittableRandom(seed))));

    static LongStream seeds() {
        return LongStream.range(0, 200);
    }

    static Stream<Tiling> tilings() {
        return TILINGS.stream();
    }

    /** Every tiling with each of 200 seeds. */
    static Stream<Arguments> tilingsAndSeeds() {
        return TILINGS.stream().flatMap(t -> seeds().mapToObj(seed -> Arguments.of(t, seed)));
    }

    /** Every edge with no neighbour lies along one side of the tessellation's bounds. */
    private static void assertTilingEndsOnlyAtTheBounds(Layout layout, long seed) {
        List<Position> bounds = bounds(layout.cells());
        for (Cell cell : layout.cells()) {
            List<Position> polygon = cell.polygon();
            for (int i = 0; i < polygon.size(); i++) {
                if (cell.neighbours().get(i).cell() == null) {
                    Position a = polygon.get(i);
                    Position b = polygon.get((i + 1) % polygon.size());
                    assertThat(onOneSide(a, b, bounds))
                            .as("seed %d: cell %s edge %s-%s has no neighbour inside the bounds", seed, cell.id(), a, b)
                            .isTrue();
                }
            }
        }
    }

    private static List<Position> bounds(List<Cell> cells) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Cell cell : cells) {
            for (Position p : cell.polygon()) {
                box[0] = Math.min(box[0], p.x());
                box[1] = Math.min(box[1], p.y());
                box[2] = Math.max(box[2], p.x());
                box[3] = Math.max(box[3], p.y());
            }
        }
        return List.of(new Position(box[0], box[1]), new Position(box[2], box[3]));
    }

    private static boolean onOneSide(Position a, Position b, List<Position> bounds) {
        Position lo = bounds.get(0);
        Position hi = bounds.get(1);
        return near(a.x(), lo.x()) && near(b.x(), lo.x()) || near(a.x(), hi.x()) && near(b.x(), hi.x())
                || near(a.y(), lo.y()) && near(b.y(), lo.y()) || near(a.y(), hi.y()) && near(b.y(), hi.y());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    @ParameterizedTest
    @MethodSource("tilingsAndSeeds")
    void levelsTileUpToTheirBounds(Tiling tiling, long seed) {
        assertTilingEndsOnlyAtTheBounds(tiling.make().apply(seed), seed);
    }

    @ParameterizedTest
    @MethodSource("tilings")
    void aLayoutHoldsItsOpenCellsAsFloorWithConsistentValues(Tiling tiling) {
        for (long seed = 0; seed < 20; seed++) {
            Layout layout = tiling.make().apply(seed);
            List<Cell> floor = layout.cells().stream().filter(c -> c.kind() == Cell.Kind.FLOOR).toList();
            assertThat(floor).extracting(Cell::polygon).containsExactlyInAnyOrderElementsOf(layout.openCells());
            assertThat(layout.cells()).extracting(Cell::id).doesNotHaveDuplicates();
            assertThat(layout.cellMesh().cells()).isEqualTo(layout.cells());
            assertThat(layout.cellMesh().recomputed().cells()).isEqualTo(layout.cells());
        }
    }

    @ParameterizedTest
    @MethodSource("tilings")
    void cellIdsAreDrawnFromTheSeedAndAreNotNamedDraws(Tiling tiling) {
        assertThat(tiling.make().apply(3).cells()).isEqualTo(tiling.make().apply(3).cells());
        assertThat(tiling.make().apply(3).cells()).extracting(Cell::id)
                .isNotEqualTo(tiling.make().apply(4).cells().stream().map(Cell::id).toList());
        assertThat(tiling.make().apply(3).draws()).extracting(Recorder.Named::name)
                .allMatch(name -> !name.contains("cell:") && !name.startsWith("cell"));
    }

    @Test
    void voronoiDrawsAreThoseOfTheGeneratorAlone() {
        // The draws are those of the generator alone: ids come after them and are not recorded.
        Recorder recorder = new Recorder(new SplittableRandom(3));
        VoronoiGraph graph = new VoronoiGraph(W, H, WorldGen.sites(W, H, DIVER),
                WorldGen.VoronoiSettings.DEFAULT.relaxations(), recorder);
        new CaveGenerator(WorldGen.VoronoiSettings.DEFAULT.caves()).generate(graph, recorder, DIVER + WorldGen.MARGIN);
        Layout voronoi = WorldGen.voronoi(W, H, DIVER, WorldGen.VoronoiSettings.DEFAULT, new SplittableRandom(3));
        assertThat(voronoi.draws()).isEqualTo(recorder.draws());
    }

    @ParameterizedTest
    @MethodSource("tilingsAndSeeds")
    void wallsFromCellsAreTheSameRockAsThePolygonWalls(Tiling tiling, long seed) {
        Layout layout = tiling.make().apply(seed);
        var material = Materials.all().getFirst();
        List<Entity> fromCells = layout.cellWalls(material);
        List<Entity> polygons = layout.walls(material);
        assertThat(fromCells).hasSameSizeAs(polygons);
        Set<String> named = new HashSet<>();
        for (Entity wall : fromCells) {
            assertThat(wall.cell()).isNotNull();
            assertThat(wall.polygon()).isNull();
            assertThat(wall.material()).isEqualTo(material.name());
            assertThat(named.add(wall.cell())).isTrue();
        }
        Set<List<Position>> cellPolygons = new HashSet<>();
        layout.cells().stream().filter(c -> named.contains(c.id())).forEach(c -> cellPolygons.add(c.polygon()));
        assertThat(cellPolygons).isEqualTo(new HashSet<>(polygons.stream().map(Entity::polygon).toList()));
        double total = fromCells.stream().mapToDouble(Entity::mass).sum();
        assertThat(total).isCloseTo(polygons.stream().mapToDouble(Entity::mass).sum(),
                org.assertj.core.api.Assertions.within(1e-6 * total));
    }
}
