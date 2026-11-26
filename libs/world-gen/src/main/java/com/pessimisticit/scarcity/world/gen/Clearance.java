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
        return of(graph, open, walls(graph, open));
    }

    /** {@link #of(CellGraph, BitSet)} against these walls, as {@link #walls} gives them. */
    static double[] of(CellGraph graph, BitSet open, PolygonGrid walls) {
        double[] clearance = new double[graph.size()];
        Arrays.fill(clearance, Double.POSITIVE_INFINITY);
        open.stream().forEach(c -> clearance[c] = nearest(graph.centroid(c), walls));
        return clearance;
    }

    /** The solid cells that touch an open one (share a vertex with it): the walls, in a grid. */
    static PolygonGrid walls(CellGraph graph, BitSet open) {
        Set<Position> vertices = new HashSet<>();
        open.stream().forEach(c -> vertices.addAll(graph.polygon(c)));
        List<List<Position>> walls = new ArrayList<>();
        double sizes = 0.0;
        for (int c = 0; c < graph.size(); c++) {
            if (!open.get(c) && graph.polygon(c).stream().anyMatch(vertices::contains)) {
                walls.add(graph.polygon(c));
                sizes += extent(graph.polygon(c));
            }
        }
        return new PolygonGrid(walls, walls.isEmpty() ? 1.0 : Math.max(0.05, sizes / walls.size()));
    }

    private static double extent(List<Position> polygon) {
        double x0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (Position p : polygon) {
            x0 = Math.min(x0, p.x());
            x1 = Math.max(x1, p.x());
            y0 = Math.min(y0, p.y());
            y1 = Math.max(y1, p.y());
        }
        return Math.max(x1 - x0, y1 - y0);
    }

    /**
     * The distance from {@code p} to the nearest wall: searching ever wider round {@code p} until
     * the nearest found lies inside the reach searched, so no wall outside it can be nearer.
     */
    static double nearest(Position p, PolygonGrid walls) {
        double reach = 1.0;
        while (true) {
            double[] best = {Double.POSITIVE_INFINITY};
            walls.near(p.x(), p.y(), p.x(), p.y(), reach, i -> best[0] = Math.min(best[0], toWall(p, walls.polygon(i))));
            if (best[0] <= reach || reach > walls.span()) {
                return best[0];
            }
            reach *= 2;
        }
    }

    private static double toWall(Position p, List<Position> wall) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < wall.size(); i++) {
            best = Math.min(best, toSegment(p, wall.get(i), wall.get((i + 1) % wall.size())));
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
