package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;

/** {@code decay}: after {@code params.duration}, destroys the entity. Started by effects. */
public final class Decay implements Action<Decay.Params> {

    public static final Decay INSTANCE = new Decay();

    private Decay() {
    }

    public record Params(@JsonProperty(required = true) double duration) {
        public Params {
            Check.positive(duration, "duration");
        }
    }

    @Override
    public String name() {
        return "decay";
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
    public double duration(Params params, EntityState self) {
        return params.duration();
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(Destroy.INSTANCE.call(new Destroy.Params(self.id())));
    }
}
