package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code wait}, open when the level has no stairs down to go to, or when there is no way to them
 * ({@link Stairway#route}): something is in the way, and waiting gives it its turn to move.
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
    public boolean open(TurnFrame turn, Entity self, World world) {
        Optional<Stairs> stairs = stairway.down(turn, self, world);
        return stairs.isEmpty() || !Stairway.inReach(self, stairs.get())
                && stairway.route(turn, self, world).filter(r -> !r.isEmpty()).isEmpty();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        return Plan.of(Wait.INSTANCE.spec(params));
    }
}
