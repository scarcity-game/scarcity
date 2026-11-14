package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.EntityState;

/**
 * {@code damage}: lowers the target's {@code hp} by a gaussian draw
 * truncated to [{@code min}, {@code max}), not below 0. A target that is
 * gone or has no {@code hp} is skipped, with no draw.
 */
public final class Damage implements Effect<Damage.Params> {

    public static final Damage INSTANCE = new Damage();

    private Damage() {
    }

    public record Params(
            @JsonProperty(required = true) String target,
            @JsonProperty(required = true) double mean,
            @JsonProperty(required = true) double sd,
            @JsonProperty(required = true) double min,
            @JsonProperty(required = true) double max) {
        public Params {
            Check.text(target, "target");
            new DamageRoll(mean, sd, min, max);
        }

        public static Params of(String target, DamageRoll roll) {
            return new Params(target, roll.mean(), roll.sd(), roll.min(), roll.max());
        }
    }

    /** The result when the target's hp changed. */
    public record Result(double hpBefore, double hpAfter) {
    }

    /** The result when there was nothing to damage. */
    public record Skipped(String skipped) {
    }

    @Override
    public String name() {
        return "damage";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        EntityState target = ctx.exists(params.target()) ? ctx.peek(params.target()) : null;
        if (target == null || target.hp() == null) {
            ctx.result(new Skipped("no hp"));
            return;
        }
        Draw.Gaussian draw = ctx.draw("damage", Draws.truncatedGaussian(
                ctx.random(), params.mean(), params.sd(), params.min(), params.max()));
        double before = target.hp();
        double after = Math.max(0.0, before - draw.value());
        ctx.update(params.target(), s -> s.withHp(after));
        ctx.result(new Result(before, after));
    }
}
