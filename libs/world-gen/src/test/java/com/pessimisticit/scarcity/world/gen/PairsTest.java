package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every {@link Tessellation} with every {@link Generator} (#386): each pair makes levels a passer can cross. */
class PairsTest {

    private static final double DIVER = 0.3;
    private static final double W = 24.0;
    private static final double H = 18.0;
    private static final double NEED = DIVER + WorldGen.MARGIN;

    /** A one-cell square corridor is {@code 2 NEED} wide, so its centroids are {@code NEED} from the walls to rounding. */
    private static final double ROUNDING = 1e-6;

    private static final List<Tessellation> TESSELLATIONS = List.of(new Tessellation.Squares(),
            Tessellation.Voronoi.DEFAULT, Tessellation.Penrose.DEFAULT,
            new Tessellation.Hexagons(), new Tessellation.Rhombille());

    private static final List<Generator> GENERATORS = List.of(
            new RoomsAndCorridors(RoomsAndCorridors.Settings.DEFAULT), new CaveGenerator(CaveGenerator.Settings.DEFAULT));

    static Stream<Arguments> pairs() {
        return TESSELLATIONS.stream().flatMap(t -> GENERATORS.stream().flatMap(g -> Stream.iterate(0L, s -> s + 1)
                .limit(20).map(seed -> Arguments.of(t.type(), g.type(), t, g, seed))));
    }

    @ParameterizedTest(name = "{0} with {1}, seed {4}")
    @MethodSource("pairs")
    void theStairsAreJoinedForThePasser(String tessellationType, String generatorType, Tessellation tessellation,
            Generator generator, long seed) {
        Recorder recorder = new Recorder(new SplittableRandom(seed));
        CellGraph graph = tessellation.make(W, H, WorldGen.cellSize(DIVER), recorder);
        Plan plan = generator.generate(graph, recorder, NEED);
        BitSet open = plan.open();

        assertThat(plan.up()).isNotEqualTo(plan.down());
        assertThat(open.get(plan.up()) && open.get(plan.down())).isTrue();
        assertThat(new Navigation(graph, open, NEED - ROUNDING).regions()).as("the disc's regions")
                .anyMatch(r -> r.get(plan.up()) && r.get(plan.down()));
        assertThat(plan.spawns()).isNotEmpty().allMatch(open::get).noneMatch(c -> c == plan.up() || c == plan.down());
        assertThat(open.stream()).noneMatch(graph::onBorder);
    }

    @ParameterizedTest(name = "{0} with {1}, seed {4}")
    @MethodSource("pairs")
    void makeIsTheTessellationThenTheGenerator(String tessellationType, String generatorType,
            Tessellation tessellation, Generator generator, long seed) {
        Layout layout = WorldGen.make(W, H, DIVER, tessellation, generator, new SplittableRandom(seed));
        Recorder recorder = new Recorder(new SplittableRandom(seed));
        CellGraph graph = tessellation.make(W, H, WorldGen.cellSize(DIVER), recorder);
        Plan plan = generator.generate(graph, recorder, NEED);

        assertThat(layout.up()).isEqualTo(graph.centroid(plan.up()));
        assertThat(layout.down()).isEqualTo(graph.centroid(plan.down()));
        assertThat(layout.draws()).isEqualTo(recorder.draws());
        assertThat(layout.cells()).hasSize(graph.size());
    }
}
