package com.pessimisticit.scarcity.orbital;

import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.controller.World;
import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.orbital.OrbitalProperties.ThrowSettings;
import com.pessimisticit.scarcity.controller.World.WorldStatus;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Projectile;
import com.pessimisticit.scarcity.world.model.rules.Throw;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@code throw} at the target's current position, read from world each time. */
@Component
public final class ThrowChoice implements Choice {

    private static final int NOT_FOUND = 404;

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
        return Throw.INSTANCE.name();
    }

    @Override
    public Plan plan(TurnFrame turn, Entity self, World world) {
        Position at;
        try {
            at = world.entity(target).position();
        } catch (WorldStatus e) {
            if (e.status() == NOT_FOUND) {
                throw new Unavailable("target " + target + " not found", e);
            }
            throw e;
        }
        return Plan.of(Throw.INSTANCE.spec(new Throw.Params(at, spread, recovery, projectile)));
    }
}
