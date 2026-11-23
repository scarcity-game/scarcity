package com.pessimisticit.scarcity.microbe;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step} a fixed distance in a direction drawn uniformly from {@code [0, 2π)}, the
 * {@code heading} draw of the intent's {@code reported.plan}. A step toward a wall is interrupted
 * where the mouse meets it (docs/actions.md, "Interruption"), so the point isn't kept inside
 * anything.
 */
@Component
public final class ScurryChoice implements Choice {

    private static final double TURN = 2 * Math.PI;

    private final double distance;
    private final RandomGenerator rng;

    @Autowired
    public ScurryChoice(MicrobeProperties microbeProperties, RandomGenerator rng) {
        this(microbeProperties.stepSettings().distance(), rng);
    }

    ScurryChoice(double distance, RandomGenerator rng) {
        this.distance = distance;
        this.rng = rng;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        Draw.Uniform heading = Draws.uniform(rng, 0.0, TURN);
        Position to = toward(self, heading.value());
        return new Plan(Step.INSTANCE.spec(new Step.Params(to)), Map.of("heading", heading));
    }

    /** {@code distance} from the entity at {@code angle}. */
    Position toward(Entity self, double angle) {
        Position from = self.position();
        return new Position(from.x() + distance * Math.cos(angle), from.y() + distance * Math.sin(angle));
    }
}
