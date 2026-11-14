package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.EntityState;

/**
 * {@code break}: a bernoulli draw with {@code p}. True: destroys the target
 * and creates the {@code debris} where it was, decaying. False: the target
 * itself decays. A target that is gone is skipped, with no draw.
 */
public final class Break implements Effect<Break.Params> {

    public static final Break INSTANCE = new Break();

    private Break() {
    }

    public record Params(
            @JsonProperty(required = true) String target,
            @JsonProperty(required = true) double p,
            @JsonProperty(required = true) EntityTemplate debris,
            @JsonProperty(required = true) Decay.Params decay) {
        public Params {
            Check.text(target, "target");
            Check.probability(p, "p");
            Check.required(debris, "debris");
            Check.required(decay, "decay");
        }
    }

    @Override
    public String name() {
        return "break";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        if (!ctx.exists(params.target())) {
            return;
        }
        boolean broke = ctx.draw("break", Draws.bernoulli(ctx.random(), params.p())).value();
        if (broke) {
            EntityState rubble = params.debris().at(ctx.peek(params.target()).position());
            ctx.trigger(Destroy.INSTANCE.call(new Destroy.Params(params.target())));
            ctx.trigger(Create.INSTANCE.call(
                    new Create.Params(ctx.areaId(), rubble, Decay.INSTANCE.spec(params.decay()))));
        } else {
            ctx.startAction(params.target(), Decay.INSTANCE.spec(params.decay()));
        }
    }
}
