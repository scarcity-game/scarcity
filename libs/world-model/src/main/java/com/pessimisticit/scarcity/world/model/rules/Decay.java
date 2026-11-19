package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.List;

/** {@code decay}: after {@code params.duration}, destroys the entity. Started by effects. */
public final class Decay implements Action<Decay.Params> {

    private final Effect<Destroy.Params> destroy;

    public Decay(Effect<Destroy.Params> destroy) {
        this.destroy = destroy;
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
    public double duration(Params params, Entity self) {
        return params.duration();
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(destroy.call(new Destroy.Params(self.id())));
    }
}
