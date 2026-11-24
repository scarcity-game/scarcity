package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes cells that meet share their vertices exactly: every vertex within {@link #TOLERANCE} of an
 * earlier one becomes that one, so a Voronoi vertex computed by three cells with different
 * rounding is one {@link Position} in all three. Edges shorter than the tolerance disappear with
 * it, and a cell left with fewer than three vertices is dropped (it has no area to speak of). A vertex
 * that the move leaves reflex in a cell is dropped from that cell.
 */
final class VertexSnap {

    /** Vertices closer than this are one, in metres. */
    static final double TOLERANCE = 0.02;

    private final Map<Long, List<Position>> buckets;

    private VertexSnap() {
        this.buckets = new HashMap<>();
    }

    /** The polygons with shared vertices equal, tiny edges merged and collapsed cells removed (order kept). */
    static List<List<Position>> snap(List<List<Position>> polygons) {
        VertexSnap snap = new VertexSnap();
        List<List<Position>> out = new ArrayList<>(polygons.size());
        for (List<Position> polygon : polygons) {
            List<Position> snapped = snap.polygon(polygon);
            if (snapped.size() >= 3) {
                out.add(snapped);
            }
        }
        return out;
    }

    private List<Position> polygon(List<Position> vertices) {
        List<Position> out = new ArrayList<>(vertices.size());
        for (Position v : vertices) {
            Position p = canonical(v);
            if (out.isEmpty() || !out.getLast().equals(p)) {
                out.add(p);
            }
        }
        while (out.size() > 1 && out.getFirst().equals(out.getLast())) {
            out.removeLast();
        }
        return out.stream().distinct().count() == out.size() ? convex(out) : List.of();
    }

    /**
     * Moving vertices by up to the tolerance can leave one of this cell's vertices on the wrong
     * side of its neighbours' edges: those are dropped from this cell, which then still covers
     * the neighbours' shared vertex to within the tolerance.
     */
    private static List<Position> convex(List<Position> vertices) {
        List<Position> out = new ArrayList<>(vertices);
        boolean dropped = true;
        while (dropped && out.size() >= 3) {
            dropped = false;
            for (int i = 0; i < out.size() && !dropped; i++) {
                Position a = out.get((i + out.size() - 1) % out.size());
                Position b = out.get(i);
                Position c = out.get((i + 1) % out.size());
                if ((b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x()) < 0.0) {
                    out.remove(i);
                    dropped = true;
                }
            }
        }
        return out;
    }

    private Position canonical(Position v) {
        long bx = (long) Math.floor(v.x() / TOLERANCE);
        long by = (long) Math.floor(v.y() / TOLERANCE);
        for (long x = bx - 1; x <= bx + 1; x++) {
            for (long y = by - 1; y <= by + 1; y++) {
                for (Position p : buckets.getOrDefault(key(x, y), List.of())) {
                    if (p.distanceTo(v) < TOLERANCE) {
                        return p;
                    }
                }
            }
        }
        buckets.computeIfAbsent(key(bx, by), k -> new ArrayList<>()).add(v);
        return v;
    }

    private static long key(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }
}
