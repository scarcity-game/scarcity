package com.pessimisticit.scarcity.diver;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.rules.Step;
import com.pessimisticit.scarcity.world.model.rules.Traverse;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step} along the way to the stairs down ({@link Stairway#route}), feasible while they are out
 * of reach and there is a way: toward its first waypoint, at most {@code diver.step.max-distance}.
 * On the last leg it stops no closer than half the reach, so the next turn can take them. The way
 * is planned again every turn, so a step cut short, or an entity that moved, costs nothing. With
 * no way ({@code wait} is feasible then), something is in the way that may move.
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
    public Plan plan(Situation situation) {
        Optional<Stairs> stairs = stairway.down(situation);
        if (stairs.isEmpty()) {
            return Plan.none("no stairs down in " + situation.turnFrame().area());
        }
        if (Stairway.inReach(situation.entity(), stairs.get())) {
            return Plan.none("the stairs down are in reach");
        }
        Optional<List<Position>> route = stairway.route(situation).filter(r -> !r.isEmpty());
        if (route.isEmpty()) {
            return Plan.none("no way to the stairs down");
        }
        double stopShort = route.get().size() == 1 ? STOP_SHORT : 0.0;
        Position from = situation.entity().position();
        return Plan.of(Step.INSTANCE.spec(new Step.Params(toward(from, route.get().getFirst(), stopShort))));
    }

    /** The point {@code min(maxDistance, distance - stopShort)} from {@code from} toward {@code to}. */
    Position toward(Position from, Position to, double stopShort) {
        double distance = from.distanceTo(to);
        double length = Math.min(maxDistance, distance - stopShort);
        double x = from.x() + (to.x() - from.x()) * length / distance;
        double y = from.y() + (to.y() - from.y()) * length / distance;
        return new Position(x, y);
    }
}
