package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * A convex polygon cut by a line into the piece left of it and the piece right of it, and the
 * points where the line crosses an edge (each a new vertex of both pieces, and of the cell across
 * that edge). A vertex within {@link #ON_LINE} of the line is on it and goes to both pieces.
 *
 * @param left    the piece left of the line (from {@code a} toward {@code b}), counter-clockwise
 * @param right   the piece right of it
 * @param crossed the edges the line crosses between their ends, with the point where
 */
record Split(List<Position> left, List<Position> right, List<Crossing> crossed) {

    /** Metres from the line within which a vertex counts as on it. */
    static final double ON_LINE = 1e-9;
    /** The least area of a piece, in square metres. */
    static final double MIN_AREA = 0.01;
    /** The least thickness of a piece (twice its area over its perimeter), in metres. */
    static final double MIN_THICKNESS = 0.02;

    /** The line crosses the edge {@code from} to {@code to} at {@code at}. */
    record Crossing(Position from, Position to, Position at) {
    }

    /**
     * Cuts {@code polygon} by the line through {@code a} and {@code b}.
     *
     * @throws IllegalArgumentException if the line doesn't pass through the polygon's inside, or a
     *     piece would be smaller than {@link #MIN_AREA} or thinner than {@link #MIN_THICKNESS}
     */
    static Split of(List<Position> polygon, Position a, Position b) {
        double length = a.distanceTo(b);
        if (length == 0.0) {
            throw new IllegalArgumentException("line: needs two different points");
        }
        int n = polygon.size();
        double[] side = new double[n];
        for (int i = 0; i < n; i++) {
            double s = Plane.cross(a, b, polygon.get(i)) / length;
            side[i] = Math.abs(s) <= ON_LINE ? 0.0 : s;
        }
        List<Position> left = new ArrayList<>();
        List<Position> right = new ArrayList<>();
        List<Crossing> crossed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Position v = polygon.get(i);
            if (side[i] >= 0.0) {
                left.add(v);
            }
            if (side[i] <= 0.0) {
                right.add(v);
            }
            int j = (i + 1) % n;
            if (side[i] * side[j] < 0.0) {
                Position at = Plane.along(v, polygon.get(j), side[i] / (side[i] - side[j]));
                left.add(at);
                right.add(at);
                crossed.add(new Crossing(v, polygon.get(j), at));
            }
        }
        require(left, "left");
        require(right, "right");
        return new Split(List.copyOf(left), List.copyOf(right), List.copyOf(crossed));
    }

    private static void require(List<Position> piece, String which) {
        double area = area(piece);
        if (piece.size() < 3 || area < MIN_AREA) {
            throw new IllegalArgumentException("line: the " + which + " piece would be " + area
                    + " m², under " + MIN_AREA + " (or the line misses the cell)");
        }
        double thickness = 2.0 * area / perimeter(piece);
        if (thickness < MIN_THICKNESS) {
            throw new IllegalArgumentException("line: the " + which + " piece would be " + thickness
                    + " m thick, under " + MIN_THICKNESS);
        }
    }

    private static double area(List<Position> piece) {
        double sum = 0.0;
        int n = piece.size();
        for (int i = 0; i < n; i++) {
            Position p = piece.get(i);
            Position q = piece.get((i + 1) % n);
            sum += p.x() * q.y() - q.x() * p.y();
        }
        return sum / 2.0;
    }

    private static double perimeter(List<Position> piece) {
        double sum = 0.0;
        int n = piece.size();
        for (int i = 0; i < n; i++) {
            sum += piece.get(i).distanceTo(piece.get((i + 1) % n));
        }
        return sum;
    }

    /**
     * {@code polygon} (of the cell across a crossed edge, where the edge runs {@code to} then
     * {@code from}) with {@code at} put between them.
     */
    static List<Position> insert(List<Position> polygon, Crossing crossing) {
        List<Position> result = new ArrayList<>(polygon.size() + 1);
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            result.add(polygon.get(i));
            if (polygon.get(i).equals(crossing.to()) && polygon.get((i + 1) % n).equals(crossing.from())) {
                result.add(crossing.at());
            }
        }
        if (result.size() != n + 1) {
            throw new IllegalStateException("polygon: has no edge " + crossing.to() + " to " + crossing.from());
        }
        return result;
    }
}
