package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Shape;
import java.util.Map;
import java.util.Optional;

/**
 * A free spot for a new mob next to its den: on the ring at the den's radius + the mob's radius +
 * {@link #MARGIN} from the den's centre, at an angle drawn uniformly in [0, 2π) and recorded as
 * {@code spot}. Up to {@link #TRIES} angles are tried. A spot is free if the mob, moved there from
 * the den's centre, touches no obstacle on the way: the same geometry as a step
 * ({@link Shape#contact}), so walls and entities that don't {@code overlap} count alike, and a
 * spot outside the area or behind a wall is not reachable.
 */
final class Placement {

    static final int TRIES = 8;
    static final double MARGIN = 0.05;

    private Placement() {
    }

    /** The first free spot, or none after {@link #TRIES} draws. */
    static Optional<Position> find(EffectContext ctx, String denId, Entity mob) {
        Entity den = ctx.peek(denId);
        double distance = den.radius() + mob.radius() + MARGIN;
        for (int i = 0; i < TRIES; i++) {
            double angle = ctx.draw("spot", Draws.uniform(ctx.random(), 0.0, 2 * Math.PI)).value();
            Position spot = new Position(den.position().x() + distance * Math.cos(angle),
                    den.position().y() + distance * Math.sin(angle));
            if (free(ctx, denId, mob, spot)) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    private static boolean free(EffectContext ctx, String denId, Entity mob, Position spot) {
        if (mob.overlap()) {
            return true;
        }
        Position from = ctx.peek(denId).position();
        for (Map.Entry<String, Entity> other : ctx.entities().entrySet()) {
            Shape shape = other.getValue().shape();
            if (shape == null || other.getValue().overlap() || other.getKey().equals(denId)) {
                continue;
            }
            if (shape.contact(from, spot.x() - from.x(), spot.y() - from.y(), mob.radius()) <= 1.0) {
                return false;
            }
        }
        return true;
    }
}
