package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a disc of a given clearance can go in a set of open cells, on any {@link CellGraph}
 * (#312): the <em>navigable</em> cells are the open ones whose centroid is at least {@code need}
 * from every wall, and two adjacent navigable cells are joined when the straight line between
 * their centroids is at least {@code need} from every wall too. A disc whose radius is
 * {@code need} (the passer's, with room to spare) can follow any chain of joined cells, so cells
 * in one region are all reachable by it. The walls are those {@link CellGraph#walls} would make.
 */
final class Navigation {

    private final CellGraph graph;
    private final BitSet open;
    private final double need;
    private final double[] clearance;
    private final List<List<Position>> walls;

    Navigation(CellGraph graph, BitSet open, double need) {
        this.graph = graph;
        this.open = open;
        this.walls = new ArrayList<>();
        this.need = need;
        this.clearance = Clearance.of(graph, open);
        Set<Position> vertices = new HashSet<>();
        open.stream().forEach(c -> vertices.addAll(graph.polygon(c)));
        for (int c = 0; c < graph.size(); c++) {
            if (!open.get(c) && graph.polygon(c).stream().anyMatch(vertices::contains)) {
                walls.add(graph.polygon(c));
            }
        }
    }

    /** How far the cell's centroid is from the nearest wall; infinite for rock. */
    double clearance(int cell) {
        return clearance[cell];
    }

    boolean navigable(int cell) {
        return open.get(cell) && clearance[cell] >= need;
    }

    /** The groups of navigable cells joined to each other, each a set of cells, the biggest first. */
    List<BitSet> regions() {
        List<BitSet> regions = new ArrayList<>();
        BitSet seen = new BitSet(graph.size());
        for (int c = 0; c < graph.size(); c++) {
            if (navigable(c) && !seen.get(c)) {
                BitSet region = region(c);
                seen.or(region);
                regions.add(region);
            }
        }
        regions.sort((a, b) -> Integer.compare(b.cardinality(), a.cardinality()));
        return regions;
    }

    private BitSet region(int start) {
        BitSet seen = new BitSet(graph.size());
        Deque<Integer> queue = new ArrayDeque<>();
        seen.set(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (int n : graph.neighbours(cell)) {
                if (!seen.get(n) && navigable(n) && joined(cell, n)) {
                    seen.set(n);
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** Whether the line between the centroids of two adjacent cells is at least {@code need} from every wall. */
    boolean joined(int a, int b) {
        Position from = graph.centroid(a);
        Position to = graph.centroid(b);
        for (List<Position> wall : walls) {
            if (distance(from, to, wall) < need) {
                return false;
            }
        }
        return true;
    }

    /** From the segment {@code from}-{@code to} to a convex polygon outside it. */
    private static double distance(Position from, Position to, List<Position> polygon) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            if (crosses(from, to, a, b)) {
                return 0.0;
            }
            best = Math.min(best, Math.min(Math.min(toSegment(from, a, b), toSegment(to, a, b)),
                    Math.min(toSegment(a, from, to), toSegment(b, from, to))));
        }
        return best;
    }

    private static boolean crosses(Position p, Position q, Position a, Position b) {
        double d1 = cross(p, q, a);
        double d2 = cross(p, q, b);
        double d3 = cross(a, b, p);
        double d4 = cross(a, b, q);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double cross(Position a, Position b, Position c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static double toSegment(Position p, Position a, Position b) {
        double ex = b.x() - a.x();
        double ey = b.y() - a.y();
        double s = Math.clamp(((p.x() - a.x()) * ex + (p.y() - a.y()) * ey) / (ex * ex + ey * ey), 0.0, 1.0);
        return Math.hypot(p.x() - a.x() - s * ex, p.y() - a.y() - s * ey);
    }
}
