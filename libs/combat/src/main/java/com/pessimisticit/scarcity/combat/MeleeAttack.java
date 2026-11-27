package com.pessimisticit.scarcity.combat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Attributes;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.Damage;
import com.pessimisticit.scarcity.world.model.rules.DamageRoll;
import com.pessimisticit.scarcity.world.model.rules.Effect;
import com.pessimisticit.scarcity.world.model.rules.EffectContext;

/**
 * {@code melee-attack}: {@code source}'s blow at {@code target}. The hit is a gaussian with mean the
 * source's {@code level} and sd {@link #HIT_SD}; it hits when the value is higher than the target's
 * {@code level} (0 for a target with none). A hit triggers {@code damage}: a gaussian with mean the
 * source's {@code strength} and sd {@link #DAMAGE_SD} of it, truncated to [0, largest double), so
 * never negative and with no real upper bound. Hit or miss, the source joins the target's {@code foes}
 * (docs/resources.md, "Factions"), before the draws.
 */
public final class MeleeAttack implements Effect<MeleeAttack.Params> {

    public static final String NAME = "melee-attack";

    /** The hit draw's sd, in levels. */
    public static final double HIT_SD = 1.0;

    /** The damage draw's sd, as a fraction of the striker's strength. */
    public static final double DAMAGE_SD = 0.1;

    private final Effect<Damage.Params> damage;

    public MeleeAttack(Effect<Damage.Params> damage) {
        this.damage = damage;
    }

    public record Params(
            @JsonProperty(required = true) String source,
            @JsonProperty(required = true) String target) {
        public Params {
            Check.text(source, "source");
            Check.text(target, "target");
        }
    }

    /** The result of a blow: the level the hit draw had to beat, and whether it did. */
    public record Result(double targetLevel, boolean hit) {
    }

    /** The result when there was nothing to strike, or nobody to strike with. */
    public record Skipped(String skipped) {
    }

    /** The damage a hit by {@code striker} draws. */
    public static DamageRoll damageRoll(Attributes striker) {
        return new DamageRoll(striker.strength(), DAMAGE_SD * striker.strength(), 0.0, Double.MAX_VALUE);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        if (!ctx.exists(params.source()) || !ctx.exists(params.target())) {
            ctx.result(new Skipped("gone"));
            return;
        }
        Attributes striker = ctx.peek(params.source()).attributes();
        Entity target = ctx.peek(params.target());
        if (striker == null || !target.damageable()) {
            ctx.result(new Skipped(striker == null ? "no attributes" : "no hp"));
            return;
        }
        ctx.update(params.target(), e -> e.withFoe(params.source()));
        double targetLevel = target.attributes() == null ? 0.0 : target.attributes().level();
        Draw.Gaussian roll = ctx.draw("hit", Draws.gaussian(ctx.random(), striker.level(), HIT_SD));
        boolean hit = roll.value() > targetLevel;
        ctx.result(new Result(targetLevel, hit));
        if (hit) {
            ctx.trigger(damage.call(Damage.Params.of(params.target(), damageRoll(striker))));
        }
    }
}
