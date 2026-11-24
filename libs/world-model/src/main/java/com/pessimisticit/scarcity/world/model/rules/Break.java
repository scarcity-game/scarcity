package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.ItemTemplate;

/**
 * {@code break}: a landing projectile becomes an item (docs/resources.md, "Items and stashes"). A
 * bernoulli draw with {@code p}: true, the {@code debris} item of the target's mass; false, one
 * {@code item} of it. Either way the target is destroyed and the item {@code settle}s where it was.
 * A target that is gone is skipped, with no draw.
 */
public final class Break implements Effect<Break.Params> {

    private final Effect<Destroy.Params> destroy;
    private final Effect<Settle.Params> settle;

    public Break(Effect<Destroy.Params> destroy, Effect<Settle.Params> settle) {
        this.destroy = destroy;
        this.settle = settle;
    }

    public record Params(
            @JsonProperty(required = true) String target,
            @JsonProperty(required = true) double p,
            @JsonProperty(required = true) ItemTemplate item,
            @JsonProperty(required = true) ItemTemplate debris) {
        public Params {
            Check.text(target, "target");
            Check.probability(p, "p");
            Check.required(item, "item");
            Check.required(debris, "debris");
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
        Entity target = ctx.peek(params.target());
        ItemTemplate becomes = broke ? params.debris() : params.item();
        ctx.trigger(destroy.call(new Destroy.Params(params.target())));
        ctx.trigger(settle.call(new Settle.Params(target.position(), becomes.of(target.mass()))));
    }
}
