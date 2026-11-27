package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.controller.Choice.Plan;
import com.pessimisticit.scarcity.controller.Choice.Situation;
import com.pessimisticit.scarcity.controller.ClearStep;
import com.pessimisticit.scarcity.pathfinding.Pathfinder;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where a brawler with nobody in range goes (#477): the roam, planned once per turn (the draws are
 * made once) and shared by {@link RoamChoice} and {@link WaitChoice}, which waits only when there is
 * nowhere to roam to. Up to {@link #TRIES} points are drawn, each by a heading uniform in
 * {@code [0, 2π)} and a distance uniform in {@code [limit/2, limit]} (where {@code limit} is the
 * step's reach, at most {@code brawler.step.max-distance}), recorded as {@code heading.n} and
 * {@code distance.n}. The first point with a way to it ({@link Pathfinder#path}) is stepped toward.
 */
@Component
public class Roaming {

    /** How many points are drawn before it gives up and waits. */
    public static final int TRIES = 5;

    private static final double TURN = 2 * Math.PI;

    private final double maxDistance;
    private final RandomGenerator rng;
    private Situation situation;
    private Plan lastPlan;

    @Autowired
    public Roaming(BrawlerProperties brawlerProperties, RandomGenerator rng) {
        this(brawlerProperties.stepSettings().maxDistance(), rng);
    }

    Roaming(double maxDistance, RandomGenerator rng) {
        this.maxDistance = maxDistance;
        this.rng = rng;
    }

    /** The roam for this turn: feasible with a step toward a drawn point, or not with why. */
    public synchronized Plan plan(Situation turn) {
        if (turn != situation) {
            lastPlan = draw(turn);
            situation = turn;
        }
        return lastPlan;
    }

    private Plan draw(Situation turn) {
        Entity self = turn.entity();
        double limit = Math.min(maxDistance, Step.reach(self));
        if (self.radius() == null || limit <= 0.0) {
            return Plan.none("cannot step");
        }
        Map<String, Draw> draws = new LinkedHashMap<>();
        for (int i = 1; i <= TRIES; i++) {
            Draw.Uniform heading = Draws.uniform(rng, 0.0, TURN);
            Draw.Uniform distance = Draws.uniform(rng, limit / 2, limit);
            draws.put("heading." + i, heading);
            draws.put("distance." + i, distance);
            Position point = ClearStep.along(self.position(), heading.value(), distance.value());
            Optional<List<Position>> route = Pathfinder.path(turn.areaView(), self.id(), point);
            if (route.isPresent() && !route.get().isEmpty()) {
                Position to = ChaseChoice.toward(self.position(), route.get().getFirst(), limit);
                return Plan.of(Step.INSTANCE.spec(new Step.Params(to)), draws);
            }
        }
        return Plan.none("no way to any of " + TRIES + " points", draws);
    }
}
