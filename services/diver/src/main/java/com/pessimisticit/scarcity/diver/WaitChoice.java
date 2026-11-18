package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import org.springframework.stereotype.Component;

/** {@code wait}, open only when the level has no stairs down to go to. */
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
    public boolean open(TurnFrame turn, Entity self, World world) {
        return stairway.down(turn, self, world).isEmpty();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        return Plan.of(Wait.INSTANCE.spec(params));
    }
}
