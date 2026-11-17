package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Placeholder;
import com.pessimisticit.scarcity.world.model.Position;

/**
 * {@code appear}: replaces {@code placeholder} by the entity it holds, at the
 * placeholder's position and with the entity's own id.
 */
public final class Appear implements Effect<Appear.Params> {

    public static final Appear INSTANCE = new Appear();

    private Appear() {
    }

    public record Params(@JsonProperty(required = true) String placeholder) {
        public Params {
            Check.text(placeholder, "placeholder");
        }
    }

    /** The {@code effect} result: who arrived, and where. */
    public record Result(String entity, Position at) {
    }

    @Override
    public String name() {
        return "appear";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Placeholder placeholder = (Placeholder) ctx.peek(params.placeholder());
        ctx.destroy(params.placeholder());
        String id = ctx.create(ctx.areaId(), placeholder.entity().withPosition(placeholder.position()));
        ctx.result(new Result(id, placeholder.position()));
    }
}
