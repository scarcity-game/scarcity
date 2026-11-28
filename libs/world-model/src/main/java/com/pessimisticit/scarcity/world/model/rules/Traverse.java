package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * {@code traverse}: takes the stairs {@code params.stairs}, within {@link #REACH}
 * of the entity. Stairs up that lead nowhere (depth 0) can't be taken. It takes {@link #DURATION}; at its end the {@code depart}
 * effect removes the entity from the area (docs/actions.md, "Crossing areas").
 */
public final class Traverse implements Action<Traverse.Params> {

    public static final String NAME = "traverse";

    /** How close the entity must be to the stairs, in distance units. */
    public static final double REACH = 1.0;

    /** Time units a crossing takes. */
    public static final double DURATION = 1.0;

    private final Effect<Depart.Params> depart;

    public Traverse(Effect<Depart.Params> depart) {
        this.depart = depart;
    }

    /** The spec of a crossing, which needs no collaborators. */
    public static ActionSpec specFor(Params params) {
        return new ActionSpec(NAME, WorldJson.toObject(params));
    }

    public record Params(@JsonProperty(required = true) String stairs) {
        public Params {
            Check.text(stairs, "stairs");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public LogTag logTag(Params params, String self, AreaView areaView) {
        return LogTag.MOVEMENT;
    }

    @Override
    public Timing timing() {
        return Timing.END;
    }

    @Override
    public double duration(Params params, Entity self) {
        return DURATION;
    }

    @Override
    public void validate(Params params, Actor self) {
        Entity stairs = self.areaView().entities().get(params.stairs());
        if (stairs == null) {
            throw new InvalidAction("stairs: no entity " + params.stairs() + " in this area");
        }
        if (!(stairs instanceof Stairs taken)) {
            throw new InvalidAction("stairs: " + params.stairs() + " is not stairs");
        }
        if (!taken.leadsDown() && taken.to() == null) {
            throw new InvalidAction("stairs: " + params.stairs() + " lead up, and there is no level above");
        }
        double distance = self.entity().position().distanceTo(stairs.position());
        if (distance > REACH) {
            throw new InvalidAction("stairs: " + distance + " away, more than the reach of " + REACH);
        }
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(depart.call(new Depart.Params(self.id(), params.stairs())));
    }
}
