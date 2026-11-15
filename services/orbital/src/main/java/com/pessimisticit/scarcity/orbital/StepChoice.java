package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.orbital.OrbitalProperties.StepSettings;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Step;

/**
 * {@code step} to the next point of the orbit: the entity's actual angle around the centre
 * plus {@code arc / radius}, on the circle of the configured radius. The angle comes from
 * where the entity is, so a short step (blocked, say) doesn't make the radius drift.
 */
public final class StepChoice implements Choice {

    private final StepSettings orbit;

    public StepChoice(StepSettings orbit) {
        if (!(orbit.radius() > 0 && Double.isFinite(orbit.radius()))) {
            throw new IllegalArgumentException("orbital.step.radius: must be positive, got " + orbit.radius());
        }
        if (!Double.isFinite(orbit.arc())) {
            throw new IllegalArgumentException("orbital.step.arc: must be finite, got " + orbit.arc());
        }
        this.orbit = orbit;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public ActionSpec plan(Entity self, World world) {
        return Step.INSTANCE.spec(new Step.Params(next(self.state().position())));
    }

    /** The point {@code arc} further round the orbit from {@code from}'s angle. */
    Position next(Position from) {
        Position c = orbit.center();
        double angle = Math.atan2(from.y() - c.y(), from.x() - c.x()) + orbit.arc() / orbit.radius();
        return new Position(c.x() + orbit.radius() * Math.cos(angle), c.y() + orbit.radius() * Math.sin(angle));
    }
}
