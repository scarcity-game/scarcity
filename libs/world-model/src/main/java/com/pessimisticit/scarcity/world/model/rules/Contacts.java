package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.Sweep;
import java.util.Map;

/**
 * The exact time an entity moving in a straight line first touches another entity or the area's
 * boundary (docs/actions.md, "Interruption"). The mover is a circle: its {@code radius} (0 if it has
 * none) keeps it that far from the boundary and from the shape ({@link Shape}) of every entity that
 * doesn't {@code overlap}, whatever that shape is. Both may be moving, each at its own constant
 * velocity.
 */
public final class Contacts {

    /** Distance within which two edges count as touching, and a contact at the end of a path as not early. */
    public static final double TOUCH = Sweep.TOUCH;

    private Contacts() {
    }

    /**
     * The first contact: {@code time} from now, and the {@code other} entity (null: the boundary).
     * Infinity when there is none.
     */
    public record Contact(double time, String other) {
    }

    /**
     * The first contact of the entity {@code id}, moving by {@code motion}, with another entity
     * (moving by its entry in {@code motions}, else standing still) or the boundary. Of equal times
     * the entity with the lower id; an entity wins over the boundary. A mover that {@code overlap}s
     * touches only the boundary. Time 0 is a mover that already touches what it moves toward.
     */
    public static Contact first(AreaView view, Map<String, Motion> motions, String id, Motion motion) {
        Entity mover = view.peek(id);
        double radius = mover.radius() == null ? 0.0 : mover.radius();
        Contact nearest = new Contact(Double.POSITIVE_INFINITY, null);
        if (!mover.overlap()) {
            for (Map.Entry<String, Entity> e : view.entities().entrySet()) {
                Entity s = e.getValue();
                Shape shape = s.shape();
                if (shape == null || s.overlap() || e.getKey().equals(id)) {
                    continue;
                }
                Motion other = motions.getOrDefault(e.getKey(), Motion.STILL);
                double t = shape.contact(mover.position(), motion.vx() - other.vx(), motion.vy() - other.vy(), radius);
                if (t < nearest.time()) {
                    nearest = new Contact(t, e.getKey());
                }
            }
        }
        double wall = boundary(view.area(), mover.position(), motion, radius);
        return nearest.time() <= wall ? nearest : new Contact(wall, null);
    }

    /** When the mover's edge reaches the boundary; 0 if it is there or beyond and moving out. */
    static double boundary(Area area, Position at, Motion motion, double radius) {
        double t = Math.min(axis(at.x(), motion.vx(), area.width(), radius),
                axis(at.y(), motion.vy(), area.height(), radius));
        return Math.max(0.0, t);
    }

    private static double axis(double start, double velocity, double size, double radius) {
        if (velocity > 0.0) {
            return (size - radius - start) / velocity;
        }
        if (velocity < 0.0) {
            return (radius - start) / velocity;
        }
        return Double.POSITIVE_INFINITY;
    }
}
