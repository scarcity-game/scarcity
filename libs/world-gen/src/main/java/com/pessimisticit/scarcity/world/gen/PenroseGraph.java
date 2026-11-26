package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Penrose rhomb tiling (P3) of the box [0, width] by [0, height], aperiodic: made by de Bruijn's
 * pentagrid method. Five families of parallel lines, family {@code j} at right angles to the unit
 * vector {@code e_j} at {@code rotation + 72° j} and offset by {@code γ_j}, cross; each crossing of
 * two lines is one rhomb, thick (72°) or thin (36°), each side {@code edge} metres. The offsets
 * {@code γ_0..γ_3} and the rotation are drawn ({@code penrose.offset[j]},
 * {@code penrose.rotation}); {@code γ_4} is minus their sum, so the tiling is a true Penrose one.
 *
 * <p>Every vertex is {@code Σ K_j e_j} for five integers {@code K}: its key. A vertex's position
 * is computed once per key, so rhombs that meet hold equal {@link Position}s and the tiling is
 * edge to edge with no T-junction. Rhombs that cross the box are clipped to it; where a shared
 * edge crosses a side, both cells compute the crossing from the edge's ends in the same order, and
 * a point on a side has that side's coordinate exactly. Pieces that collapse to nothing are left
 * out.
 */
public final class PenroseGraph implements CellGraph {

    private static final int FAMILIES = 5;

    private final double width;
    private final double height;
    private final double edge;
    private final double[] ex;
    private final double[] ey;
    private final double[] offset;
    private final Map<List<Integer>, Position> vertices;
    private final PolygonGraph graph;

    public PenroseGraph(double width, double height, double edge, Recorder recorder) {
        if (!(width > 0.0) || !(height > 0.0) || !(edge > 0.0)) {
            throw new IllegalArgumentException("penrose: need a positive box and edge");
        }
        this.width = width;
        this.height = height;
        this.edge = edge;
        this.ex = new double[FAMILIES];
        this.ey = new double[FAMILIES];
        this.offset = new double[FAMILIES];
        this.vertices = new HashMap<>();
        double sum = 0.0;
        for (int j = 0; j < FAMILIES - 1; j++) {
            offset[j] = recorder.uniform("penrose.offset[" + j + "]", 0.0, 1.0);
            sum += offset[j];
        }
        offset[FAMILIES - 1] = -sum;
        double rotation = recorder.uniform("penrose.rotation", 0.0, 2 * Math.PI / FAMILIES);
        for (int j = 0; j < FAMILIES; j++) {
            ex[j] = Math.cos(rotation + 2 * Math.PI * j / FAMILIES);
            ey[j] = Math.sin(rotation + 2 * Math.PI * j / FAMILIES);
        }
        this.graph = new PolygonGraph(rhombs(), new Position(0.0, 0.0), new Position(width, height));
    }

    /** Every rhomb that reaches into the box, clipped to it, by family pair and line. */
    private List<List<Position>> rhombs() {
        // A vertex lies near 2.5 * edge * x for the grid point x it comes from (Σ e_j e_j^T = 2.5 I).
        double reach = Math.hypot(width, height) / 2 / (2.5 * edge) + 2.0;
        List<List<Position>> cells = new ArrayList<>();
        for (int r = 0; r < FAMILIES; r++) {
            for (int s = r + 1; s < FAMILIES; s++) {
                crossings(r, s, reach, cells);
            }
        }
        return cells;
    }

    /** Adds the rhomb of every crossing of a line of family {@code r} with one of family {@code s}. */
    private void crossings(int r, int s, double reach, List<List<Position>> cells) {
        for (int kr = lowest(r, reach); kr <= highest(r, reach); kr++) {
            for (int ks = lowest(s, reach); ks <= highest(s, reach); ks++) {
                List<Position> piece = BoxClip.toBox(rhomb(r, s, kr, ks), width, height);
                if (piece != null) {
                    cells.add(piece);
                }
            }
        }
    }

    private int lowest(int j, double reach) {
        return (int) Math.floor(-reach - offset[j]) - 1;
    }

    private int highest(int j, double reach) {
        return (int) Math.ceil(reach - offset[j]) + 1;
    }

    /** The rhomb where line {@code kr} of family {@code r} crosses line {@code ks} of family {@code s}, counter-clockwise. */
    private List<Position> rhomb(int r, int s, int kr, int ks) {
        double det = ex[r] * ey[s] - ey[r] * ex[s];
        double br = kr + offset[r];
        double bs = ks + offset[s];
        double x = (br * ey[s] - ey[r] * bs) / det;
        double y = (ex[r] * bs - br * ex[s]) / det;
        int[] k = new int[FAMILIES];
        for (int j = 0; j < FAMILIES; j++) {
            k[j] = (int) Math.ceil(x * ex[j] + y * ey[j] - offset[j]);
        }
        List<Position> rhomb = new ArrayList<>(4);
        int[][] corners = {{kr, ks}, {kr + 1, ks}, {kr + 1, ks + 1}, {kr, ks + 1}};
        for (int[] c : corners) {
            k[r] = c[0];
            k[s] = c[1];
            rhomb.add(vertex(k));
        }
        // From (kr, ks) the sides run along e_r then e_s: counter-clockwise when e_s is left of e_r.
        return det > 0 ? rhomb : rhomb.reversed();
    }

    private Position vertex(int[] k) {
        return vertices.computeIfAbsent(Arrays.stream(k).boxed().toList(), key -> {
            double x = width / 2;
            double y = height / 2;
            for (int j = 0; j < FAMILIES; j++) {
                x += edge * key.get(j) * ex[j];
                y += edge * key.get(j) * ey[j];
            }
            return new Position(x, y);
        });
    }

    /** The side of every rhomb, in metres. */
    public double edge() {
        return edge;
    }

    @Override
    public int size() {
        return graph.size();
    }

    @Override
    public List<Position> polygon(int cell) {
        return graph.polygon(cell);
    }

    @Override
    public Position centroid(int cell) {
        return graph.centroid(cell);
    }

    @Override
    public List<Integer> neighbours(int cell) {
        return graph.neighbours(cell);
    }

    @Override
    public boolean onBorder(int cell) {
        return graph.onBorder(cell);
    }

    @Override
    public double minEdge() {
        return graph.minEdge();
    }

    @Override
    public List<Position> bounds() {
        return graph.bounds();
    }
}
