package com.pessimisticit.scarcity.microbe;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import org.springframework.stereotype.Component;

/** {@code wait} for the configured duration. */
@Component
public final class WaitChoice implements Choice {

    private final Wait.Params params;

    public WaitChoice(MicrobeProperties microbeProperties) {
        this.params = new Wait.Params(microbeProperties.waitSettings().duration());
    }

    @Override
    public String name() {
        return Wait.INSTANCE.name();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        return Plan.of(Wait.INSTANCE.spec(params));
    }
}
