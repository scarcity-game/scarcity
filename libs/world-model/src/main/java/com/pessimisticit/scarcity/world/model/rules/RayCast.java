package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import java.util.Map;

/**
 * The line cast that {@code line-attack} and {@code step} share: from a
 * point along a unit direction to the first thing it meets: the edge of an
 * obstacle (an entity that doesn't {@code overlap}, whatever its
 * {@link Shape}, a wall among them). A mover with a {@code clearance} (its
 * own radius) keeps that far from it. In an enclosed area every line meets
 * a wall.
 */
final class RayCast {

    private RayCast() {
    }

    /** Where the line stops: {@code t} along it, and the entity met there. */
    record Hit(double t, String entity) {
    }

    /**
     * The first obstacle along {@code from + t * (dx, dy)}, {@code t >= 0},
     * ignoring the entity {@code ignore}. Of two entities met at the same
     * {@code t}, the lower id.
     *
     * @throws IllegalStateException if it meets nothing: the area isn't enclosed
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
        if (nearest.entity() == null) {
            throw new IllegalStateException("a line from " + from + " along (" + dx + ", " + dy
                    + ") meets nothing: area " + view.areaId() + " isn't enclosed by walls (docs/resources.md, \"Walls\")");
        }
        return nearest;
    }

    static Position along(Position from, double dx, double dy, double t) {
        return new Position(from.x() + t * dx, from.y() + t * dy);
    }
}
