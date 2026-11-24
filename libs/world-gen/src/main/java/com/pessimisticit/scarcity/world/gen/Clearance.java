package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** How far each open cell's centroid is from the nearest wall: the walls {@link CellGraph#walls} would make. */
final class Clearance {

    private Clearance() {
    }

    /** Per cell, the distance from its centroid to the nearest wall cell; infinite for rock and when there is no wall. */
    static double[] of(CellGraph graph, BitSet open) {
        Set<Position> vertices = new HashSet<>();
        open.stream().forEach(c -> vertices.addAll(graph.polygon(c)));
        List<List<Position>> walls = new ArrayList<>();
        for (int c = 0; c < graph.size(); c++) {
            if (!open.get(c) && graph.polygon(c).stream().anyMatch(vertices::contains)) {
                walls.add(graph.polygon(c));
            }
        }
        double[] clearance = new double[graph.size()];
        Arrays.fill(clearance, Double.POSITIVE_INFINITY);
        open.stream().forEach(c -> clearance[c] = nearest(graph.centroid(c), walls));
        return clearance;
    }

    private static double nearest(Position p, List<List<Position>> walls) {
        double best = Double.POSITIVE_INFINITY;
        for (List<Position> wall : walls) {
            for (int i = 0; i < wall.size(); i++) {
                best = Math.min(best, toSegment(p, wall.get(i), wall.get((i + 1) % wall.size())));
            }
        }
        return best;
    }

    private static double toSegment(Position p, Position a, Position b) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double s = Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / (ex * ex + ey * ey), 0.0, 1.0);
        return Math.hypot(p.x() - a.x() - s * ex, p.y() - a.y() - s * ey);
    }
}
