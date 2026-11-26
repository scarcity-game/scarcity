package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * Distances to the blocked part of a mesh: every non-floor cell, and every edge where the tiling
 * ends (docs/resources.md, "Cells"). A best-first search from the query's own cells, through floor
 * cells only, finds blockers nearest first. The way from a point to its nearest blocker crosses
 * only floor cells, each no farther than that blocker, so the search can stop there.
 */
final class Blocked {

    /** How many equal steps a width samples along its edge before narrowing on the widest. */
    static final int SAMPLES = 32;
    /** Golden-section steps that narrow on the widest point: the bracket shrinks to about 1e-8 of it. */
    private static final int REFINE = 40;
    private static final double GOLDEN = (Math.sqrt(5.0) - 1.0) / 2.0;

    /** Something that blocks: a non-floor cell's polygon, or an edge where the tiling ends. */
    private record Blocker(List<Position> polygon, Position from, Position to) {
        double distance(Position p) {
            return polygon != null ? Plane.toPolygon(p, polygon) : Plane.toSegment(p, from, to);
        }
    }

    /** What the search measures from: a point or a segment. */
    private interface Query {
        double toPolygon(List<Position> polygon);

        double toSegment(Position from, Position to);
    }

    /** A cell or a tiling-end edge waiting in the search, by its distance from the query. */
    private record Entry(double distance, String cell, Position from, Position to) {
    }

    /** Told each blocker as it is found, nearest first; the search stops at {@link #bound}. */
    private interface Visitor {
        double bound();

        void found(Blocker blocker, double distance);
    }

    private final MeshIndex index;

    Blocked(MeshIndex index) {
        this.index = index;
    }

    /** From the centroid of a floor cell to the nearest blocker. */
    double clearance(String floor, Position centroid) {
        double[] best = {Double.POSITIVE_INFINITY};
        Query query = new Query() {
            @Override
            public double toPolygon(List<Position> polygon) {
                return Plane.toPolygon(centroid, polygon);
            }

            @Override
            public double toSegment(Position from, Position to) {
                return Plane.toSegment(centroid, from, to);
            }
        };
        search(query, List.of(floor), new Visitor() {
            @Override
            public double bound() {
                return best[0];
            }

            @Override
            public void found(Blocker blocker, double distance) {
                best[0] = Math.min(best[0], distance);
            }
        });
        return best[0];
    }

    /**
     * Twice the greatest distance from a point of the edge {@code from} to {@code to}, between
     * the floor cells {@code a} and {@code b}, to the nearest blocker: the widest disc that crosses
     * it. Blockers are gathered nearest the edge first, until none left can be nearer than the
     * widest point so far.
     */
    double width(String a, String b, Position from, Position to) {
        List<Blocker> blockers = new ArrayList<>();
        double[] peak = {Double.POSITIVE_INFINITY};
        DoubleUnaryOperator gap = t -> nearest(blockers, Plane.along(from, to, t));
        Query query = new Query() {
            @Override
            public double toPolygon(List<Position> polygon) {
                return Plane.segmentToPolygon(from, to, polygon);
            }

            @Override
            public double toSegment(Position p, Position q) {
                return Plane.segmentToSegment(from, to, p, q);
            }
        };
        search(query, List.of(a, b), new Visitor() {
            @Override
            public double bound() {
                return peak[0];
            }

            @Override
            public void found(Blocker blocker, double distance) {
                blockers.add(blocker);
                peak[0] = peak(gap);
            }
        });
        return 2.0 * peak[0];
    }

    private void search(Query query, Collection<String> seeds, Visitor visitor) {
        PriorityQueue<Entry> queue = new PriorityQueue<>((x, y) -> Double.compare(x.distance(), y.distance()));
        Set<String> seen = new HashSet<>(seeds);
        seeds.forEach(id -> queue.add(new Entry(query.toPolygon(index.polygon(id)), id, null, null)));
        while (!queue.isEmpty() && queue.peek().distance() < visitor.bound()) {
            Entry entry = queue.poll();
            if (entry.cell() == null) {
                visitor.found(new Blocker(null, entry.from(), entry.to()), entry.distance());
            } else if (!index.open(entry.cell())) {
                visitor.found(new Blocker(index.polygon(entry.cell()), null, null), entry.distance());
            } else {
                expand(query, entry.cell(), seen, queue);
            }
        }
    }

    private void expand(Query query, String id, Set<String> seen, PriorityQueue<Entry> queue) {
        List<Position> polygon = index.polygon(id);
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            String other = index.across(id, i);
            if (other == null) {
                Position from = polygon.get(i);
                Position to = polygon.get((i + 1) % n);
                queue.add(new Entry(query.toSegment(from, to), null, from, to));
            } else if (seen.add(other)) {
                queue.add(new Entry(query.toPolygon(index.polygon(other)), other, null, null));
            }
        }
    }

    private static double nearest(List<Blocker> blockers, Position p) {
        double best = Double.POSITIVE_INFINITY;
        for (Blocker blocker : blockers) {
            best = Math.min(best, blocker.distance(p));
        }
        return best;
    }

    /**
     * The greatest value of {@code f} on [0, 1]: the best of {@link #SAMPLES} equal steps, then a
     * golden-section search in the steps either side of it. {@code f} is the least of convex
     * distances, so it peaks where two of them cross or at an end; the sampling finds that peak's
     * neighbourhood unless two peaks lie within one step, an error under 1/64 of the edge.
     */
    static double peak(DoubleUnaryOperator f) {
        int best = 0;
        double bestValue = f.applyAsDouble(0.0);
        for (int k = 1; k <= SAMPLES; k++) {
            double value = f.applyAsDouble((double) k / SAMPLES);
            if (value > bestValue) {
                best = k;
                bestValue = value;
            }
        }
        double lo = Math.max(0.0, (best - 1.0) / SAMPLES);
        double hi = Math.min(1.0, (best + 1.0) / SAMPLES);
        for (int i = 0; i < REFINE; i++) {
            double m1 = hi - GOLDEN * (hi - lo);
            double m2 = lo + GOLDEN * (hi - lo);
            if (f.applyAsDouble(m1) < f.applyAsDouble(m2)) {
                lo = m1;
            } else {
                hi = m2;
            }
        }
        return Math.max(bestValue, f.applyAsDouble((lo + hi) / 2.0));
    }
}
