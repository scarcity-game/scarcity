package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Step;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step} straight toward the stairs down, open while they are out of reach: at most
 * {@code diver.step.max-distance}, and no closer than half the reach, so the next turn can take
 * them. Closed while the step would start touching something it moves toward (world rejects
 * that; {@code wait} is open then). The stairs and the diver are inside the area, so every point between them is too.
 */
@Component
public final class ApproachChoice implements Choice {

    /** How far short of the stairs' centre a step stops. */
    static final double STOP_SHORT = Traverse.REACH / 2;

    private final Stairway stairway;
    private final double maxDistance;

    @Autowired
    public ApproachChoice(Stairway stairway, DiverProperties diverProperties) {
        this(stairway, diverProperties.stepSettings().maxDistance());
    }

    ApproachChoice(Stairway stairway, double maxDistance) {
        this.stairway = stairway;
        this.maxDistance = maxDistance;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public boolean open(TurnFrame turn, Entity self, World world) {
        return stairway.down(turn, self, world).filter(s -> !Stairway.inReach(self, s)).isPresent()
                && !stairway.blocked(turn, self, world);
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        Stairs stairs = stairway.down(turn, self, world)
                .orElseThrow(() -> new Unavailable("no stairs down in " + turn.area()));
        return Plan.of(Step.INSTANCE.spec(new Step.Params(toward(self.position(), stairs.position()))));
    }

    /** The point {@code min(maxDistance, distance - STOP_SHORT)} from {@code from} toward {@code to}. */
    Position toward(Position from, Position to) {
        double distance = from.distanceTo(to);
        double length = Math.min(maxDistance, distance - STOP_SHORT);
        double x = from.x() + (to.x() - from.x()) * length / distance;
        double y = from.y() + (to.y() - from.y()) * length / distance;
        return new Position(x, y);
    }
}
