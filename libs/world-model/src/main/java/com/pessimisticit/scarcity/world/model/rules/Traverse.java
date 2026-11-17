package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;

/**
 * {@code traverse}: takes the stairs {@code params.stairs}, within {@link #REACH}
 * of the entity. It takes {@link #DURATION}; at its end the {@code depart}
 * effect removes the entity from the area (docs/actions.md, "Crossing areas").
 */
public final class Traverse implements Action<Traverse.Params> {

    public static final Traverse INSTANCE = new Traverse();

    /** How close the entity must be to the stairs, in distance units. */
    public static final double REACH = 1.0;

    /** Time units a crossing takes. */
    public static final double DURATION = 1.0;

    private Traverse() {
    }

    public record Params(@JsonProperty(required = true) String stairs) {
        public Params {
            Check.text(stairs, "stairs");
        }
    }

    @Override
    public String name() {
        return "traverse";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
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
        if (!(stairs instanceof Stairs)) {
            throw new InvalidAction("stairs: " + params.stairs() + " is not stairs");
        }
        double distance = self.entity().position().distanceTo(stairs.position());
        if (distance > REACH) {
            throw new InvalidAction("stairs: " + distance + " away, more than the reach of " + REACH);
        }
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(Depart.INSTANCE.call(new Depart.Params(self.id(), params.stairs())));
    }
}
