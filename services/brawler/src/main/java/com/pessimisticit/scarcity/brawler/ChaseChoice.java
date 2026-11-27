package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.List;
import java.util.Optional;

/**
 * The chase half of {@link StepChoice}: {@code step} along the way to the quarry ({@link Quarry#route}), feasible while it is out of reach
 * and there is a way: toward the first waypoint, at most its step's reach ({@link Step#reach}) and at
 * most {@code brawler.step.max-distance}. The way is planned again every turn, so a quarry that
 * moved costs nothing.
 */
public final class ChaseChoice implements Choice {

    private final Quarry quarry;
    private final double maxDistance;

    ChaseChoice(Quarry quarry, double maxDistance) {
        this.quarry = quarry;
        this.maxDistance = maxDistance;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        Optional<Entity> target = quarry.target(situation);
        if (target.isEmpty()) {
            return Plan.none("nobody to fight in range");
        }
        if (Quarry.inReach(situation.entity(), target.get())) {
            return Plan.none(target.get().id() + " is in reach");
        }
        Optional<List<Position>> route = quarry.route(situation);
        if (route.isEmpty()) {
            return Plan.none("no way to " + target.get().id());
        }
        Position from = situation.entity().position();
        double limit = Math.min(maxDistance, Step.reach(situation.entity()));
        return Plan.of(Step.INSTANCE.spec(new Step.Params(toward(from, route.get().getFirst(), limit))));
    }

    /** The point at most {@code limit} from {@code from} toward {@code to}. */
    static Position toward(Position from, Position to, double limit) {
        double distance = from.distanceTo(to);
        double length = Math.min(limit, distance);
        return new Position(from.x() + (to.x() - from.x()) * length / distance,
                from.y() + (to.y() - from.y()) * length / distance);
    }
}
