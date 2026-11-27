package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code wait}, feasible when there is nobody to fight in range, or the quarry is out of reach with
 * no way to it ({@link Quarry#route}): something is in the way, and waiting gives it its turn to move.
 */
@Component
public final class WaitChoice implements Choice {

    private final Quarry quarry;
    private final Wait.Params params;

    public WaitChoice(Quarry quarry, BrawlerProperties brawlerProperties) {
        this.quarry = quarry;
        this.params = new Wait.Params(brawlerProperties.waitSettings().duration());
    }

    @Override
    public String name() {
        return Wait.INSTANCE.name();
    }

    @Override
    public Plan plan(Situation situation) {
        Optional<Entity> target = quarry.target(situation);
        if (target.isPresent() && Quarry.inReach(situation.entity(), target.get())) {
            return Plan.none(target.get().id() + " is in reach");
        }
        if (target.isPresent() && quarry.route(situation).isPresent()) {
            return Plan.none("there is a way to " + target.get().id());
        }
        return Plan.of(Wait.INSTANCE.spec(params));
    }
}
