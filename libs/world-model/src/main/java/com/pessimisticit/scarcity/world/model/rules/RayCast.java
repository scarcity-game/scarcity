package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.Map;

/**
 * The line cast that {@code line-attack} and {@code step} share: from a
 * point along a unit direction to the first thing it meets, either the edge
 * of an entity with a {@code radius} or the area's boundary. A mover with a
 * {@code clearance} (its own radius) keeps that far from both.
 */
final class RayCast {

    /** Squared distance within which a start point counts as touching a circle. */
    private static final double TOUCH = 1e-9;

    private RayCast() {
    }

    /** Where the line stops: {@code t} along it, and the entity met there (null: the boundary). */
    record Hit(double t, String entity) {
    }

    /**
     * The first obstacle along {@code from + t * (dx, dy)}, {@code t >= 0},
     * ignoring the entity {@code ignore}. Of two entities met at the same
     * {@code t}, the lower id; an entity met where the boundary is wins.
     */
    static Hit first(AreaView view, Position from, double dx, double dy, double clearance, String ignore) {
        Hit nearest = new Hit(Double.POSITIVE_INFINITY, null);
        for (Map.Entry<String, EntityState> e : view.entities().entrySet()) {
            EntityState s = e.getValue();
            if (s.radius() == null || e.getKey().equals(ignore)) {
                continue;
            }
            double t = entry(from, dx, dy, s.position(), s.radius() + clearance);
            if (t < nearest.t()) {
                nearest = new Hit(t, e.getKey());
            }
        }
        double wall = boundary(view.area(), from, dx, dy, clearance);
        return nearest.t() <= wall ? nearest : new Hit(wall, null);
    }

    static Position along(Position from, double dx, double dy, double t) {
        return new Position(from.x() + t * dx, from.y() + t * dy);
    }

    /**
     * Where the line enters the circle, or infinity. A start inside the
     * circle can leave it; a start on its edge is blocked going in (t = 0).
     */
    private static double entry(Position from, double dx, double dy, Position centre, double radius) {
        double ox = from.x() - centre.x();
        double oy = from.y() - centre.y();
        double b = dx * ox + dy * oy;
        double c = ox * ox + oy * oy - radius * radius;
        if (c < -TOUCH) {
            return Double.POSITIVE_INFINITY;
        }
        if (c <= TOUCH) {
            return b < 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        }
        double disc = b * b - c;
        double t = disc < 0.0 ? -1.0 : -b - Math.sqrt(disc);
        return t >= 0.0 ? t : Double.POSITIVE_INFINITY;
    }

    /** Where the line reaches the boundary, moved in by {@code clearance}; 0 if it starts beyond it. */
    private static double boundary(AreaState area, Position from, double dx, double dy, double clearance) {
        double t = Math.min(axis(from.x(), dx, area.width(), clearance), axis(from.y(), dy, area.height(), clearance));
        return Math.max(0.0, t);
    }

    private static double axis(double start, double d, double size, double clearance) {
        if (d > 0.0) {
            return (size - clearance - start) / d;
        }
        if (d < 0.0) {
            return (clearance - start) / d;
        }
        return Double.POSITIVE_INFINITY;
    }
}
