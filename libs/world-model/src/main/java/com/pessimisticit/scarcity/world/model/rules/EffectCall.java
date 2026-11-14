package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Objects;

/** An effect to run with its typed params, as an action or another effect sets it off. */
public record EffectCall<P extends Record>(Effect<P> effect, P params) {

    public EffectCall {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(params, "params");
    }

    /** The effect's name, for the log. */
    public String type() {
        return effect.name();
    }

    /** The params as the log writes them. */
    public ObjectNode paramsJson() {
        return WorldJson.toObject(params);
    }

    public void run(EffectContext ctx) {
        effect.run(params, ctx);
    }
}
