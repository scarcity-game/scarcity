package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Shape;
import com.pessimisticit.scarcity.world.model.Sweep;
import java.util.Map;

/**
 * The exact time an entity moving in a straight line first touches another entity, a wall among
 * them (docs/actions.md, "Interruption"). The mover is a circle: its {@code radius} (0 if it has
 * none) keeps it that far from the shape ({@link Shape}) of every entity that doesn't
 * {@code overlap}, whatever that shape is. Both may be moving, each at its own constant
 * velocity.
 */
public final class Contacts {

    /** Distance within which two edges count as touching, and a contact at the end of a path as not early. */
    public static final double TOUCH = Sweep.TOUCH;

    private Contacts() {
    }

    /**
     * The first contact: {@code time} from now, and the {@code other} entity. Infinity, and no
     * {@code other} (null), when there is none.
     */
    public record Contact(double time, String other) {
    }

    /**
     * The first contact of the entity {@code id}, moving by {@code motion}, with another entity
     * (moving by its entry in {@code motions}, else standing still). Of equal times the entity with
     * the lower id. A mover that {@code overlap}s touches nothing. Time 0 is a mover that already touches what it moves toward.
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
        return nearest;
    }
}
