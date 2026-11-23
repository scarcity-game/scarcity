package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import java.util.Map;

/**
 * The line cast that {@code line-attack} and {@code step} share: from a
 * point along a unit direction to the first thing it meets, either the edge
 * of an obstacle (an entity that doesn't {@code overlap}, whatever its
 * {@link Shape}) or the area's boundary. A mover with a
 * {@code clearance} (its own radius) keeps that far from both.
 */
final class RayCast {

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
        for (Map.Entry<String, Entity> e : view.entities().entrySet()) {
            Entity s = e.getValue();
            Shape shape = s.shape();
            if (shape == null || s.overlap() || e.getKey().equals(ignore)) {
                continue;
            }
            double t = shape.contact(from, dx, dy, clearance);
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

    /** Where the line reaches the boundary, moved in by {@code clearance}; 0 if it starts beyond it. */
    private static double boundary(Area area, Position from, double dx, double dy, double clearance) {
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
