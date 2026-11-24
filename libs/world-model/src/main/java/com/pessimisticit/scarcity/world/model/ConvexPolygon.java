package com.pessimisticit.scarcity.world.model;

import java.util.HashSet;
import java.util.List;

/**
 * The geometry of a {@link Wall}'s outline: the checks (3 or more vertices, no two equal,
 * convex, counter-clockwise) and the centroid. Vertices are in area coordinates.
 */
final class ConvexPolygon {

    private ConvexPolygon() {
    }

    /** Throws {@link IllegalArgumentException} naming {@code polygon} if the vertices are not a convex counter-clockwise polygon. */
    static List<Position> check(List<Position> vertices) {
        Check.required(vertices, "polygon");
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("polygon: needs 3 or more vertices, got " + vertices.size());
        }
        vertices.forEach(v -> Check.required(v, "polygon"));
        if (new HashSet<>(vertices).size() != vertices.size()) {
            throw new IllegalArgumentException("polygon: no two vertices may be equal");
        }
        if (!(area2(vertices) > 0.0)) {
            throw new IllegalArgumentException("polygon: must be counter-clockwise and enclose an area");
        }
        for (int i = 0; i < vertices.size(); i++) {
            requireAllLeft(vertices, i);
        }
        return List.copyOf(vertices);
    }

    /** The centre of mass of the enclosed area (the polygon must have passed {@link #check}). */
    static Position centroid(List<Position> vertices) {
        Position origin = vertices.get(0);
        double twiceArea = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int i = 1; i < vertices.size() - 1; i++) {
            Position a = vertices.get(i);
            Position b = vertices.get(i + 1);
            double cross = cross(origin, a, b);
            twiceArea += cross;
            cx += cross * (origin.x() + a.x() + b.x());
            cy += cross * (origin.y() + a.y() + b.y());
        }
        return new Position(cx / (3.0 * twiceArea), cy / (3.0 * twiceArea));
    }

    /** Every vertex is on the left of, or on, the edge from vertex {@code i} to the next: that is convex and counter-clockwise. */
    private static void requireAllLeft(List<Position> vertices, int i) {
        Position from = vertices.get(i);
        Position to = vertices.get((i + 1) % vertices.size());
        for (Position other : vertices) {
            if (cross(from, to, other) < 0.0) {
                throw new IllegalArgumentException(
                        "polygon: must be convex and counter-clockwise; vertex " + other
                                + " is right of the edge " + from + " to " + to);
            }
        }
    }

    /** The enclosed area, in square metres (the polygon must have passed {@link #check}). */
    static double area(List<Position> vertices) {
        return area2(vertices) / 2.0;
    }

    private static double area2(List<Position> vertices) {
        double sum = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            Position a = vertices.get(i);
            Position b = vertices.get((i + 1) % vertices.size());
            sum += a.x() * b.y() - b.x() * a.y();
        }
        return sum;
    }

    /** Twice the signed area of the triangle a, b, c: positive when c is left of a to b. */
    private static double cross(Position a, Position b, Position c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }
}
