package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An opening cut down to where the mover's centre may cross it: the points of the edge at least
 * the clearance from every solid polygon touching either cell. A convex polygon grown by the
 * clearance covers one stretch of a line, so each takes one interval off the edge; what is left
 * is the longest free stretch. Each end that moved records the polygon corner that stopped it,
 * for the path to turn round.
 */
final class Erosion {

    /** An opening as the mover crosses it: its ends, the corner each was cut back to (or null), and by how much. */
    record Opening(Position left, Position right, Position leftCorner, Position rightCorner, double clearance) {
    }

    private static final int STEPS = 60;

    private final FloorGraph graph;
    private final double[] clearances;
    private final Map<Position, List<Integer>> touching;
    private final List<List<Position>> solids;

    /**
     * Erosion against the graph's solid cells and {@code extra} polygons (cells refused for others
     * in them), by {@code clearance}, or by {@code least} where nothing of an opening is that clear
     * (its width is between the mover's diameter and twice the clearance).
     */
    Erosion(FloorGraph graph, double clearance, double least, List<List<Position>> extra) {
        this.graph = graph;
        this.clearances = new double[] {clearance, least};
        this.touching = new HashMap<>();
        this.solids = new ArrayList<>();
        List<List<Position>> all = new ArrayList<>(graph.solids);
        all.addAll(extra);
        for (List<Position> polygon : all) {
            int i = solids.size();
            solids.add(polygon);
            for (Position v : polygon) {
                touching.computeIfAbsent(v, k -> new ArrayList<>()).add(i);
            }
        }
    }

    /** The opening {@code link}, out of floor cell {@code from}, as the mover crosses it. */
    Opening opening(int from, int link) {
        Set<Integer> near = near(from, graph.to[link]);
        for (double clearance : clearances) {
            Opening o = opening(link, near, clearance);
            if (o != null) {
                return o;
            }
        }
        // Not even that clear anywhere: the search let it through on its width, so the middle.
        Position middle = Geometry.midpoint(graph.left[link], graph.right[link]);
        return new Opening(middle, middle, null, null, 0.0);
    }

    private Opening opening(int link, Set<Integer> near, double clearance) {
        Position left = graph.left[link];
        Position right = graph.right[link];
        double length = left.distanceTo(right);
        List<double[]> blocked = new ArrayList<>();
        List<Integer> by = new ArrayList<>();
        for (int s : near) {
            double[] cut = cut(solids.get(s), left, right, length, clearance);
            if (cut != null) {
                blocked.add(cut);
                by.add(s);
            }
        }
        double[] free = longestFree(blocked, length);
        if (free[1] < free[0]) {
            return null;
        }
        Position l = Geometry.toward(left, right, free[0]);
        Position r = Geometry.toward(left, right, free[1]);
        return new Opening(l, r, corner(free[0] > 0.0, l, blocked, by, free[0], 1, clearance),
                corner(free[1] < length, r, blocked, by, free[1], 0, clearance), clearance);
    }

    /** The solid polygons touching a vertex of either cell, ascending. */
    private Set<Integer> near(int a, int b) {
        Set<Integer> near = new LinkedHashSet<>();
        for (int cell : new int[] {a, b}) {
            for (Position v : graph.polygons.get(cell)) {
                near.addAll(touching.getOrDefault(v, List.of()));
            }
        }
        return near;
    }

    /** The stretch {@code [t0, t1]} of the edge from {@code a}, {@code length} long, within the clearance of {@code polygon}; null if none. */
    private static double[] cut(List<Position> polygon, Position a, Position b, double length, double clearance) {
        // Distance to a convex polygon is convex along a line: find its least, then each edge of the dip.
        double lo = 0.0;
        double hi = length;
        for (int i = 0; i < STEPS; i++) {
            double m1 = lo + (hi - lo) / 3;
            double m2 = hi - (hi - lo) / 3;
            if (at(polygon, a, b, m1) < at(polygon, a, b, m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double least = (lo + hi) / 2;
        if (at(polygon, a, b, least) >= clearance) {
            return null;
        }
        return new double[] {edge(polygon, a, b, least, 0.0, clearance), edge(polygon, a, b, least, length, clearance)};
    }

    /** Where, between {@code inside} (within the clearance) and {@code toward}, the edge leaves the clearance. */
    private static double edge(List<Position> polygon, Position a, Position b, double inside, double toward,
            double clearance) {
        if (at(polygon, a, b, toward) < clearance) {
            return toward;
        }
        double in = inside;
        double out = toward;
        for (int i = 0; i < STEPS; i++) {
            double mid = (in + out) / 2;
            if (at(polygon, a, b, mid) < clearance) {
                in = mid;
            } else {
                out = mid;
            }
        }
        return out;
    }

    private static double at(List<Position> polygon, Position a, Position b, double t) {
        return FloorGraph.distance(polygon, Geometry.toward(a, b, t));
    }

    /** The longest stretch of {@code [0, length]} outside every blocked interval; empty (t0 > t1) if there is none. */
    private static double[] longestFree(List<double[]> blocked, double length) {
        List<double[]> sorted = new ArrayList<>(blocked);
        sorted.sort((x, y) -> Double.compare(x[0], y[0]));
        double[] best = {0.0, -1.0};
        double from = 0.0;
        for (double[] cut : sorted) {
            if (cut[0] > from && cut[0] - from > best[1] - best[0]) {
                best = new double[] {from, cut[0]};
            }
            from = Math.max(from, cut[1]);
        }
        if (length > from && length - from > best[1] - best[0]) {
            best = new double[] {from, length};
        }
        return best;
    }

    /**
     * The corner nearest {@code p} of a polygon whose cut has its end {@code which} (0: start, 1:
     * end) at {@code t}, if it is within the clearance (plus rounding) of it.
     */
    private Position corner(boolean moved, Position p, List<double[]> blocked, List<Integer> by, double t,
            int which, double clearance) {
        if (!moved) {
            return null;
        }
        Position best = null;
        for (int i = 0; i < blocked.size(); i++) {
            double[] cut = blocked.get(i);
            if (Math.abs(cut[which] - t) > 1e-9) {
                continue;
            }
            for (Position v : solids.get(by.get(i))) {
                if (p.distanceTo(v) <= clearance + 1e-6 && (best == null || p.distanceTo(v) < p.distanceTo(best))) {
                    best = v;
                }
            }
        }
        return best;
    }
}
