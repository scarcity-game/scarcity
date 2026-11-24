package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.ClearStep;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.StepSettings;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Step;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step} to the next point of the orbit: the entity's actual angle around the centre
 * plus {@code arc / radius}, on the circle of the configured radius. The angle comes from
 * where the entity is, so a short step (blocked, say) doesn't make the radius drift. Not feasible
 * when that step isn't clear this turn ({@link ClearStep#clear}): a mouse in the way, say.
 */
@Component
public final class StepChoice implements Choice {

    private final StepSettings stepSettings;

    @Autowired
    public StepChoice(OrbitalProperties orbitalProperties) {
        this(orbitalProperties.stepSettings());
    }

    StepChoice(StepSettings stepSettings) {
        this.stepSettings = stepSettings;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        Position to = next(situation.entity().position());
        if (!ClearStep.clear(situation.areaView(), situation.entity(), to)) {
            return Plan.none("the orbit's next step to " + to + " isn't clear");
        }
        return Plan.of(Step.INSTANCE.spec(new Step.Params(to)));
    }

    /** The point {@code arc} further round the orbit from {@code from}'s angle. */
    Position next(Position from) {
        Position c = stepSettings.position();
        double angle = Math.atan2(from.y() - c.y(), from.x() - c.x()) + stepSettings.arc() / stepSettings.radius();
        return new Position(c.x() + stepSettings.radius() * Math.cos(angle), c.y() + stepSettings.radius() * Math.sin(angle));
    }
}
