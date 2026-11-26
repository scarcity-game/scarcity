package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.List;

/**
 * Sutherland-Hodgman clipping of a convex polygon to the box [0, width] by [0, height], for
 * tessellations whose cells cross it. Where an edge shared by two cells crosses a side, both cells
 * compute the crossing from the edge's lexicographically lower end, so they get the identical
 * point, and a point on a side has that side's coordinate exactly. A piece with no area is dropped.
 */
final class BoxClip {

    private static final double TINY = 1e-12;

    private BoxClip() {
    }

    /** The part of the convex {@code polygon} in [0, width] by [0, height]; null if nothing of it with any area is. */
    static List<Position> toBox(List<Position> polygon, double width, double height) {
        List<Position> p = polygon;
        p = side(p, 0, 0.0, true);
        p = side(p, 0, width, false);
        p = side(p, 1, 0.0, true);
        p = side(p, 1, height, false);
        p = distinct(p);
        return p.size() >= 3 && area(p) > TINY ? p : null;
    }

    /** Keeps the part where coordinate {@code axis} is at least (or, if not {@code above}, at most) {@code at}. */
    private static List<Position> side(List<Position> polygon, int axis, double at, boolean above) {
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            boolean aIn = inside(a, axis, at, above);
            boolean bIn = inside(b, axis, at, above);
            if (aIn) {
                out.add(a);
            }
            if (aIn != bIn) {
                out.add(crossing(a, b, axis, at));
            }
        }
        return out;
    }

    private static boolean inside(Position p, int axis, double at, boolean above) {
        double v = axis == 0 ? p.x() : p.y();
        return above ? v >= at : v <= at;
    }

    /** Where the segment crosses the line, from its lower end (by x, then y), the line's coordinate exact. */
    private static Position crossing(Position a, Position b, int axis, double at) {
        boolean aFirst = a.x() < b.x() || a.x() == b.x() && a.y() < b.y();
        Position from = aFirst ? a : b;
        Position to = aFirst ? b : a;
        if (axis == 0) {
            double t = (at - from.x()) / (to.x() - from.x());
            return new Position(at, from.y() + t * (to.y() - from.y()));
        }
        double t = (at - from.y()) / (to.y() - from.y());
        return new Position(from.x() + t * (to.x() - from.x()), at);
    }

    private static double area(List<Position> polygon) {
        double twice = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            twice += a.x() * b.y() - b.x() * a.y();
        }
        return twice / 2;
    }

    private static List<Position> distinct(List<Position> polygon) {
        List<Position> out = new ArrayList<>();
        for (Position p : polygon) {
            if (out.isEmpty() || !out.getLast().equals(p)) {
                out.add(p);
            }
        }
        while (out.size() > 1 && out.getFirst().equals(out.getLast())) {
            out.removeLast();
        }
        return out;
    }
}
