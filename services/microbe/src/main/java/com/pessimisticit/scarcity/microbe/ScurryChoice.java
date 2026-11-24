package com.pessimisticit.scarcity.microbe;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.ClearStep;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code step} a fixed distance in a direction drawn uniformly from {@code [0, 2π)}, one that is
 * clear as the area is this turn ({@link ClearStep#anyHeading}: up to ten headings, each the
 * intent's {@code reported.plan.heading.<n>}, the last one taken). With no clear heading, step
 * isn't feasible this turn and the mouse waits (#316).
 */
@Component
public final class ScurryChoice implements Choice {

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
    public Plan plan(Situation situation) {
        return ClearStep.anyHeading(situation, rng, distance);
    }
}
