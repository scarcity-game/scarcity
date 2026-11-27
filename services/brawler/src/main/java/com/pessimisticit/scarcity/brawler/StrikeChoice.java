package com.pessimisticit.scarcity.brawler;

import com.pessimisticit.scarcity.combat.Strike;
import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@code strike} the quarry ({@link Quarry#target}), feasible when it is in reach. */
@Component
public final class StrikeChoice implements Choice {

    private final Quarry quarry;

    public StrikeChoice(Quarry quarry) {
        this.quarry = quarry;
    }

    @Override
    public String name() {
        return Strike.NAME;
    }

    @Override
    public Plan plan(Situation situation) {
        Optional<Entity> target = quarry.target(situation);
        if (target.isEmpty()) {
            return Plan.none("nobody to fight in range");
        }
        if (!Quarry.inReach(situation.entity(), target.get())) {
            return Plan.none(target.get().id() + " is out of reach");
        }
        return Plan.of(Strike.specFor(target.get().id()));
    }
}
