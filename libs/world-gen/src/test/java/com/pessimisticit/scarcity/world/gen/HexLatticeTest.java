package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The hexagonal lattice behind hexagons and rhombille: both tile the box from the same vertices (#419, #420). */
class HexLatticeTest {

    private static final double W = 24.0;
    private static final double H = 18.0;
    private static final double SIDE = 0.7;

    static LongStream seeds() {
        return LongStream.range(0, 40);
    }

    private static double area(List<Position> p) {
        double twice = 0.0;
        for (int i = 0; i < p.size(); i++) {
            Position a = p.get(i);
            Position b = p.get((i + 1) % p.size());
            twice += a.x() * b.y() - b.x() * a.y();
        }
        return twice / 2;
    }

    private static CellGraph graph(Tessellation tessellation, long seed) {
        return tessellation.make(W, H, 1.0, new Recorder(new SplittableRandom(seed)));
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void hexagonsAndRhombsFillTheBoxEdgeToEdgeInTheMeanAreaAsked(long seed) {
        for (Tessellation tessellation : List.of(new Tessellation.Hexagons(), new Tessellation.Rhombille())) {
            CellGraph graph = graph(tessellation, seed);
            double total = 0.0;
            int whole = 0;
            for (int c = 0; c < graph.size(); c++) {
                assertThat(area(graph.polygon(c))).isPositive();
                total += area(graph.polygon(c));
                if (!graph.onBorder(c)) {
                    whole++;
                    assertThat(area(graph.polygon(c))).isCloseTo(1.0, offset(1e-9));
                }
            }
            assertThat(total).as(tessellation.type()).isCloseTo(W * H, offset(1e-6));
            assertThat(whole).isGreaterThan(300);
        }
    }

    @Test
    void anInteriorHexagonHasSixNeighboursAndAnInteriorRhombFour() {
        CellGraph hexagons = graph(new Tessellation.Hexagons(), 5);
        CellGraph rhombs = graph(new Tessellation.Rhombille(), 5);
        for (int c = 0; c < hexagons.size(); c++) {
            if (!hexagons.onBorder(c)) {
                assertThat(hexagons.neighbours(c)).hasSize(6);
            }
        }
        for (int c = 0; c < rhombs.size(); c++) {
            if (!rhombs.onBorder(c)) {
                assertThat(rhombs.neighbours(c)).hasSize(4);
            }
        }
    }

    @Test
    void eachRhombIsAHexagonsCentreAndThreeOfItsCorners() {
        HexLattice lattice = new HexLattice(W, H, SIDE, "t", new Recorder(new SplittableRandom(1)));
        List<List<Position>> hexagons = lattice.hexagons();
        List<List<Position>> rhombs = lattice.rhombs();
        List<Position> hexagon = hexagons.stream().filter(h -> h.size() == 6).skip(40).findFirst().orElseThrow();
        // The rhombs sharing three corners with a whole hexagon, equal in floats: its three.
        long cut = rhombs.stream().filter(r -> r.size() == 4 && r.stream().filter(hexagon::contains).count() == 3)
                .count();
        assertThat(cut).isEqualTo(3);
    }

    @Test
    void theOriginIsDrawnSoSeedsDiffer() {
        Recorder recorder = new Recorder(new SplittableRandom(3));
        new Tessellation.Hexagons().make(W, H, 1.0, recorder);
        assertThat(recorder.draws()).extracting(Recorder.Named::name).containsExactly("hexagons.origin.x",
                "hexagons.origin.y");
        assertThat(graph(new Tessellation.Hexagons(), 3).polygon(0)).isNotEqualTo(
                graph(new Tessellation.Hexagons(), 4).polygon(0));
    }
}
