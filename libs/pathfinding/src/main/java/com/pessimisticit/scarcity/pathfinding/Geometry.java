package com.pessimisticit.scarcity.pathfinding;

import com.pessimisticit.scarcity.world.model.Position;

/** Plane geometry for paths: x to the right, y up, so counter-clockwise is a left turn. */
final class Geometry {

    private Geometry() {
    }

    /** Twice the signed area of {@code a b c}: positive when {@code c} is left of the line from {@code a} to {@code b}. */
    static double cross(Position a, Position b, Position c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    /** The distance from {@code p} to the segment {@code a b}. */
    static double segmentDistance(Position a, Position b, Position p) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double length2 = dx * dx + dy * dy;
        double t = length2 == 0.0 ? 0.0
                : Math.clamp(((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / length2, 0.0, 1.0);
        return Math.hypot(a.x() + t * dx - p.x(), a.y() + t * dy - p.y());
    }

    /** The point {@code t} metres from {@code a} toward {@code b}. */
    static Position toward(Position a, Position b, double t) {
        double length = a.distanceTo(b);
        if (length == 0.0) {
            return a;
        }
        return new Position(a.x() + (b.x() - a.x()) * t / length, a.y() + (b.y() - a.y()) * t / length);
    }

    static Position midpoint(Position a, Position b) {
        return new Position((a.x() + b.x()) / 2, (a.y() + b.y()) / 2);
    }
}
