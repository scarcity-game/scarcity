package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import org.springframework.stereotype.Component;

/** {@code traverse} the stairs down, open once they are within {@link Traverse#REACH}. */
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
    public boolean open(TurnFrame turn, Entity self, World world) {
        return stairway.down(turn, self, world).filter(s -> Stairway.inReach(self, s)).isPresent();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        Stairs stairs = stairway.down(turn, self, world)
                .orElseThrow(() -> new Unavailable("no stairs down in " + turn.area()));
        return Plan.of(Traverse.specFor(new Traverse.Params(stairs.id())));
    }
}
