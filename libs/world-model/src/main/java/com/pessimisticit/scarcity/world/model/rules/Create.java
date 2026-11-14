package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;

/** {@code create}: creates an entity with {@code state} in {@code parent}, and starts {@code start} on it if given. */
public final class Create implements Effect<Create.Params> {

    public static final Create INSTANCE = new Create();

    private Create() {
    }

    /** {@code start} is null when the new entity starts idle. */
    public record Params(
            @JsonProperty(required = true) String parent,
            @JsonProperty(required = true) EntityState state,
            ActionSpec start) {
        public Params {
            Check.text(parent, "parent");
            Check.required(state, "state");
        }
    }

    @Override
    public String name() {
        return "create";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        String id = ctx.create(params.parent(), params.state());
        if (params.start() != null) {
            ctx.startAction(id, params.start());
        }
    }
}
