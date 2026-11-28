package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;

/** {@code destroy}: destroys {@code target}; an action it has in progress is cancelled. */
public final class Destroy implements Effect<Destroy.Params> {

    public static final Destroy INSTANCE = new Destroy();

    private Destroy() {
    }

    public record Params(@JsonProperty(required = true) String target) {
        public Params {
            Check.text(target, "target");
        }
    }

    @Override
    public String name() {
        return "destroy";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    /** A {@code death} when the target is damageable (it has hp), else an {@link LogTag#EVENT}. */
    @Override
    public LogTag logTag(Params params, AreaView areaView, String source) {
        if (!areaView.exists(params.target()) || !areaView.peek(params.target()).damageable()) {
            return LogTag.EVENT;
        }
        return LogTag.involving("death", 6, 9, areaView, params.target());
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        ctx.destroy(params.target());
    }
}
