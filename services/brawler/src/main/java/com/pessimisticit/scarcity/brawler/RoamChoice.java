package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.rules.Step;

/**
 * The roam half of {@link StepChoice}: {@code step} toward a point drawn by {@link Roaming}, feasible
 * only with nobody to fight in range and a way to the point. The point's draws are reported.
 */
final class RoamChoice implements Choice {

    private final Quarry quarry;
    private final Roaming roaming;

    RoamChoice(Quarry quarry, Roaming roaming) {
        this.quarry = quarry;
        this.roaming = roaming;
    }

    @Override
    public String name() {
        return Step.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        if (quarry.target(situation).isPresent()) {
            return Plan.none("there is somebody to fight");
        }
        return roaming.plan(situation);
    }
}
