package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@code traverse} the stairs down, feasible once they are within {@link Traverse#REACH}. */
@Component
public final class DescendChoice implements Choice {

    private final Stairway stairway;

    public DescendChoice(Stairway stairway) {
        this.stairway = stairway;
    }

    @Override
    public String name() {
        return Traverse.NAME;
    }

    @Override
    public Plan plan(Situation situation) {
        Optional<Stairs> stairs = stairway.down(situation);
        if (stairs.isEmpty()) {
            return Plan.none("no stairs down in " + situation.turnFrame().area());
        }
        if (!Stairway.inReach(situation.entity(), stairs.get())) {
            return Plan.none("the stairs down are out of reach");
        }
        return Plan.of(Traverse.specFor(new Traverse.Params(stairs.get().id())));
    }
}
