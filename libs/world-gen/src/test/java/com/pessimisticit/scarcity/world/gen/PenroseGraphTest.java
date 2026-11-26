package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The Penrose rhomb tiling: two rhombs, edge to edge, filling the box, aperiodic. */
class PenroseGraphTest {

    private static final double W = 24.0;
    private static final double H = 18.0;
    private static final double EDGE = 0.8;
    private static final double PHI = (1 + Math.sqrt(5)) / 2;

    static LongStream seeds() {
        return LongStream.range(0, 40);
    }

    private static PenroseGraph graph(long seed) {
        return new PenroseGraph(W, H, EDGE, new Recorder(new SplittableRandom(seed)));
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

    @ParameterizedTest
    @MethodSource("seeds")
    void theRhombsFillTheBoxEdgeToEdge(long seed) {
        PenroseGraph graph = graph(seed);
        double total = 0.0;
        List<CellMesh.Piece> pieces = new ArrayList<>();
        for (int c = 0; c < graph.size(); c++) {
            assertThat(area(graph.polygon(c))).as("counter-clockwise").isPositive();
            total += area(graph.polygon(c));
            pieces.add(new CellMesh.Piece(graph.polygon(c), !graph.onBorder(c)));
        }
        assertThat(total).isCloseTo(W * H, org.assertj.core.data.Offset.offset(1e-6));
        // The mesh refuses overlaps; every edge between cells is shared whole (no T-junction).
        int[] n = {0};
        List<Cell> cells = CellMesh.generate(pieces, () -> "cell:00000000-0000-4000-8000-%012d".formatted(++n[0])).cells();
        assertThat(cells).hasSize(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            assertThat(graph.neighbours(c)).doesNotContain(c);
        }
    }

    @Test
    void insideTheBoxThereAreTwoRhombsThickAndThinInTheGoldenRatio() {
        PenroseGraph graph = graph(7);
        Map<String, Integer> kinds = new HashMap<>();
        for (int c = 0; c < graph.size(); c++) {
            List<Position> p = graph.polygon(c);
            if (graph.onBorder(c) || p.size() != 4) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                assertThat(p.get(i).distanceTo(p.get((i + 1) % 4))).isCloseTo(EDGE, org.assertj.core.data.Offset.offset(1e-9));
            }
            double a = area(p);
            String kind = Math.abs(a - EDGE * EDGE * Math.sin(Math.toRadians(72))) < 1e-9 ? "thick"
                    : Math.abs(a - EDGE * EDGE * Math.sin(Math.toRadians(36))) < 1e-9 ? "thin" : "other";
            kinds.merge(kind, 1, Integer::sum);
        }
        assertThat(kinds).doesNotContainKey("other");
        assertThat((double) kinds.get("thick") / kinds.get("thin")).isCloseTo(PHI, org.assertj.core.data.Offset.offset(0.15));
    }

    @Test
    void theOffsetsAndRotationAreDrawnSoSeedsDiffer() {
        Recorder recorder = new Recorder(new SplittableRandom(3));
        new PenroseGraph(W, H, EDGE, recorder);
        assertThat(recorder.draws()).extracting(Recorder.Named::name).containsExactly("penrose.offset[0]",
                "penrose.offset[1]", "penrose.offset[2]", "penrose.offset[3]", "penrose.rotation");
        assertThat(graph(3).polygon(0)).isEqualTo(graph(3).polygon(0));
        assertThat(graph(3).polygon(0)).isNotEqualTo(graph(4).polygon(0));
    }

    @Test
    void noTranslationMapsTheTilingOntoItself() {
        // Aperiodic: the vectors between thick rhombs of one orientation never repeat as a lattice.
        // A weak check: no shift that maps one such rhomb onto another maps them all.
        PenroseGraph graph = graph(11);
        List<Position> centres = new ArrayList<>();
        for (int c = 0; c < graph.size(); c++) {
            if (!graph.onBorder(c)) {
                centres.add(graph.centroid(c));
            }
        }
        Position a = centres.get(centres.size() / 2);
        int periodic = 0;
        for (Position b : centres.subList(0, 200)) {
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            if (dx == 0 && dy == 0) {
                continue;
            }
            boolean all = centres.stream().filter(p -> p.x() + dx > 2 && p.x() + dx < W - 2 && p.y() + dy > 2
                    && p.y() + dy < H - 2).allMatch(p -> centres.stream().anyMatch(q -> q.distanceTo(
                            new Position(p.x() + dx, p.y() + dy)) < 1e-6));
            periodic += all ? 1 : 0;
        }
        assertThat(periodic).isZero();
    }
}
