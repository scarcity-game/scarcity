package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Timing;

/** {@code wait}: nothing, for {@code params.duration}. */
public final class Wait implements Action<Wait.Params> {

    public static final Wait INSTANCE = new Wait();

    private Wait() {
    }

    public record Params(@JsonProperty(required = true) double duration) {
        public Params {
            Check.positive(duration, "duration");
        }
    }

    @Override
    public String name() {
        return "wait";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public Timing timing() {
        return Timing.NONE;
    }

    @Override
    public double duration(Params params, EntityState self) {
        return params.duration();
    }
}
