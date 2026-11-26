package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Voronoi cells whose shared vertices are equal {@link Position}s by construction (#352). Each
 * vertex of a clipped cell lies on two lines, each a bisector with another site or a side of the
 * box ({@link VoronoiCells.Corner}); with the cell's own site they are its <em>key</em>, and the
 * position is computed once per key: the circumcentre of three sites, a bisector meeting a side,
 * or a corner of the box. Every cell meeting at a vertex has the same key, so it gets the same
 * position, and an edge between two cells is the same two positions in both: no T-junction.
 *
 * <p>Rounding can still make two keys of nearly cocircular sites land within {@link #MERGE} of
 * each other, an edge shorter than any rounding error; such positions are one, for every cell.
 */
final class VoronoiVertices {

    /** Metres within which two computed vertices are one. */
    static final double MERGE = 1e-9;
    /** How far a computed vertex may lie from the clipped one before it is a bug, in metres. */
    private static final double AGREE = 1e-6;

    private VoronoiVertices() {
    }

    /** One convex, counter-clockwise polygon per site that keeps an area, in site order. */
    static List<List<Position>> cells(double width, double height, List<Position> sites) {
        List<List<VoronoiCells.Corner>> clipped = VoronoiCells.corners(width, height, sites);
        Map<List<Integer>, Position> placed = placeAll(clipped, width, height, sites);
        Map<Position, Position> merged = merge(placed.values());
        List<List<Position>> cells = new ArrayList<>(clipped.size());
        for (int i = 0; i < clipped.size(); i++) {
            List<Position> polygon = polygon(i, clipped.get(i), placed, merged);
            if (polygon.size() >= 3) {
                cells.add(polygon);
            }
        }
        return cells;
    }

    /** Every corner's position, computed once per key, checked against where clipping put it. */
    private static Map<List<Integer>, Position> placeAll(List<List<VoronoiCells.Corner>> clipped, double width,
            double height, List<Position> sites) {
        Map<List<Integer>, Position> placed = new HashMap<>();
        for (int i = 0; i < clipped.size(); i++) {
            for (VoronoiCells.Corner corner : clipped.get(i)) {
                List<Integer> key = key(i, corner);
                Position at = placed.computeIfAbsent(key, k -> place(k, width, height, sites));
                if (at.distanceTo(corner.at()) > AGREE) {
                    throw new IllegalStateException("voronoi: vertex " + key + " computed at " + at
                            + ", clipped at " + corner.at());
                }
            }
        }
        return placed;
    }

    /** Cell {@code i}'s corners at their merged positions, a vertex merged into the one before it left out. */
    private static List<Position> polygon(int i, List<VoronoiCells.Corner> corners, Map<List<Integer>, Position> placed,
            Map<Position, Position> merged) {
        List<Position> polygon = new ArrayList<>();
        for (VoronoiCells.Corner corner : corners) {
            Position at = merged.get(placed.get(key(i, corner)));
            if (polygon.isEmpty() || !polygon.getLast().equals(at)) {
                polygon.add(at);
            }
        }
        if (polygon.size() > 1 && polygon.getFirst().equals(polygon.getLast())) {
            polygon.removeLast();
        }
        return List.copyOf(polygon);
    }

    /** The cell's site and the corner's two lines, ascending, each once: sides are negative. */
    private static List<Integer> key(int site, VoronoiCells.Corner corner) {
        return Arrays.stream(new int[] {site, corner.lineA(), corner.lineB()}).distinct().sorted().boxed().toList();
    }

    /** Where the lines of {@code key} meet, from the sites in ascending order so every cell agrees. */
    private static Position place(List<Integer> key, double width, double height, List<Position> sites) {
        List<Integer> sides = key.stream().filter(k -> k < 0).toList();
        List<Position> on = key.stream().filter(k -> k >= 0).map(sites::get).toList();
        return switch (sides.size()) {
            case 0 -> circumcentre(on.get(0), on.get(1), on.get(2));
            case 1 -> onSide(on.get(0), on.get(1), sides.getFirst(), width, height);
            default -> corner(sides.get(0), sides.get(1), width, height);
        };
    }

    private static Position circumcentre(Position a, Position b, Position c) {
        double bx = b.x() - a.x();
        double by = b.y() - a.y();
        double cx = c.x() - a.x();
        double cy = c.y() - a.y();
        double d = 2.0 * (bx * cy - by * cx);
        double b2 = bx * bx + by * by;
        double c2 = cx * cx + cy * cy;
        return new Position(a.x() + (cy * b2 - by * c2) / d, a.y() + (bx * c2 - cx * b2) / d);
    }

    /** Where the bisector of a and b meets a side: that side's coordinate is exact. */
    private static Position onSide(Position a, Position b, int side, double width, double height) {
        double rhs = b.x() * b.x() + b.y() * b.y() - a.x() * a.x() - a.y() * a.y();
        double dx = 2.0 * (b.x() - a.x());
        double dy = 2.0 * (b.y() - a.y());
        return switch (side) {
            case VoronoiCells.BOTTOM -> new Position(rhs / dx, 0.0);
            case VoronoiCells.TOP -> new Position((rhs - dy * height) / dx, height);
            case VoronoiCells.LEFT -> new Position(0.0, rhs / dy);
            case VoronoiCells.RIGHT -> new Position(width, (rhs - dx * width) / dy);
            default -> throw new IllegalArgumentException("side: " + side);
        };
    }

    private static Position corner(int s, int t, double width, double height) {
        boolean right = s == VoronoiCells.RIGHT || t == VoronoiCells.RIGHT;
        boolean top = s == VoronoiCells.TOP || t == VoronoiCells.TOP;
        return new Position(right ? width : 0.0, top ? height : 0.0);
    }

    /**
     * Each position to the one standing for every position within {@link #MERGE} of it, joined
     * transitively: the least by x then y, so the choice doesn't depend on order.
     */
    private static Map<Position, Position> merge(Collection<Position> positions) {
        List<Position> sorted = positions.stream().distinct()
                .sorted((p, q) -> p.x() != q.x() ? Double.compare(p.x(), q.x()) : Double.compare(p.y(), q.y()))
                .toList();
        int[] parent = new int[sorted.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
            for (int j = i - 1; j >= 0 && sorted.get(i).x() - sorted.get(j).x() <= MERGE; j--) {
                if (sorted.get(i).distanceTo(sorted.get(j)) <= MERGE) {
                    int a = root(parent, i);
                    int b = root(parent, j);
                    parent[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        Map<Position, Position> to = new HashMap<>();
        for (int i = 0; i < parent.length; i++) {
            to.put(sorted.get(i), sorted.get(root(parent, i)));
        }
        return to;
    }

    private static int root(int[] parent, int i) {
        int r = i;
        while (parent[r] != r) {
            r = parent[r];
        }
        return r;
    }
}
