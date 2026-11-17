package com.pessimisticit.scarcity.microbe;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.pessimisticit.scarcity.world.model.Area;
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
 * {@code heading} draw of the intent's {@code reported.plan}. The point is kept inside the turn's
 * area (a radius in from each edge, as world rejects a step out of it); a step that would then be
 * empty, in a corner, goes the opposite way.
 */
@Component
public final class ScurryChoice implements Choice {

    /** How far in from an edge the point stays, for an entity with no radius. */
    static final double EDGE = 0.01;
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
        Area area = world.area(turn.area());
        Draw.Uniform heading = Draws.uniform(rng, 0.0, TURN);
        Position to = toward(self, area, heading.value());
        if (to.equals(self.position())) {
            to = toward(self, area, heading.value() + Math.PI);
        }
        return new Plan(Step.INSTANCE.spec(new Step.Params(to)), Map.of("heading", heading));
    }

    /** {@code distance} from the entity at {@code angle}, clamped inside {@code area}. */
    Position toward(Entity self, Area area, double angle) {
        double edge = Math.max(EDGE, self.radius() == null ? 0.0 : self.radius());
        Position from = self.position();
        double x = clamp(from.x() + distance * Math.cos(angle), edge, area.width() - edge);
        double y = clamp(from.y() + distance * Math.sin(angle), edge, area.height() - edge);
        return new Position(x, y);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
