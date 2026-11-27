package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.rules.Step;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step}, the one choice for both ways of stepping, as {@code controller.weights} are by action
 * name: chase the quarry ({@link ChaseChoice}) when there is one in range, and roam ({@link RoamChoice})
 * when there is not. The two never apply together, so at most one is feasible.
 */
@Component
public final class StepChoice implements Choice {

    private final Choice chase;
    private final Choice roam;

    @Autowired
    public StepChoice(Quarry quarry, Roaming roaming, BrawlerProperties brawlerProperties) {
        this(new ChaseChoice(quarry, brawlerProperties.stepSettings().maxDistance()), new RoamChoice(quarry, roaming));
    }

    StepChoice(Choice chase, Choice roam) {
        this.chase = chase;
        this.roam = roam;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        Plan chasing = chase.plan(situation);
        return chasing.feasible() ? chasing : roam.plan(situation);
    }
}
