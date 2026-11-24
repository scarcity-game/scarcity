package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.controller.Choice;
import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.ThrowSettings;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Projectile;
import com.pessimisticit.scarcity.world.model.rules.Throw;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code throw} at the target's current position, as this turn's entities have it. Not feasible
 * when the target isn't in the thrower's area (gone, or never there).
 */
@Component
public final class ThrowChoice implements Choice {

    private final String target;
    private final double spread;
    private final double recovery;
    private final Projectile projectile;

    @Autowired
    public ThrowChoice(OrbitalProperties orbitalProperties) {
        this(orbitalProperties.throwSettings());
    }

    /** Reads the projectile now, so a bad one fails startup. */
    ThrowChoice(ThrowSettings throwSettings) {
        this.target = throwSettings.target();
        this.spread = throwSettings.spread();
        this.recovery = throwSettings.recovery();
        this.projectile = WorldJson.fromJson(WorldJson.fromJson(throwSettings.projectile(), JsonNode.class),
                Projectile.class, "orbital.throw.projectile");
    }

    @Override
    public String name() {
        return Throw.NAME;
    }

    @Override
    public Plan plan(Situation situation) {
        Entity at = situation.areaView().entities().get(target);
        if (at == null) {
            return Plan.none("target " + target + " not in " + situation.turnFrame().area());
        }
        return Plan.of(Throw.specFor(new Throw.Params(at.position(), spread, recovery, projectile)));
    }
}
