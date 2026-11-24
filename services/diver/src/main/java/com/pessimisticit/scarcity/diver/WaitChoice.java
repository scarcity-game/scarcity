package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code wait}, feasible when the level has no stairs down to go to, or when there is no way to
 * them ({@link Stairway#route}): something is in the way, and waiting gives it its turn to move.
 */
@Component
public final class WaitChoice implements Choice {

    private final Stairway stairway;
    private final Wait.Params params;

    public WaitChoice(Stairway stairway, DiverProperties diverProperties) {
        this.stairway = stairway;
        this.params = new Wait.Params(diverProperties.waitSettings().duration());
    }

    @Override
    public String name() {
        return Wait.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        Optional<Stairs> stairs = stairway.down(situation);
        if (stairs.isPresent() && Stairway.inReach(situation.entity(), stairs.get())) {
            return Plan.none("the stairs down are in reach");
        }
        if (stairs.isPresent() && stairway.route(situation).filter(r -> !r.isEmpty()).isPresent()) {
            return Plan.none("there is a way to the stairs down");
        }
        return Plan.of(Wait.INSTANCE.spec(params));
    }
}
