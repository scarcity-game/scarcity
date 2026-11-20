package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code fly}: a projectile's flight from {@code from} to {@code to}. At its
 * end the entity is at {@code to}, {@code damage} hits {@code hit} (if any)
 * and the projectile may {@code break}. Started by {@code line-attack}.
 */
public final class Fly implements Action<Fly.Params> {

    private final Effect<Damage.Params> damage;
    private final Effect<Break.Params> breakEffect;

    public Fly(Effect<Damage.Params> damage, Effect<Break.Params> breakEffect) {
        this.damage = damage;
        this.breakEffect = breakEffect;
    }

    /** {@code hit} is null for a line that met the boundary. */
    public record Params(
            @JsonProperty(required = true) Position from,
            @JsonProperty(required = true) Position to,
            @JsonProperty(required = true) double speed,
            String hit,
            @JsonProperty(required = true) DamageRoll damageRoll,
            @JsonProperty(required = true) double p,
            @JsonProperty(required = true) EntityTemplate entityTemplate,
            @JsonProperty(required = true) Decay.Params decay) {
        public Params {
            Check.required(from, "from");
            Check.required(to, "to");
            Check.positive(speed, "speed");
            Check.required(damageRoll, "damage");
            Check.probability(p, "p");
            Check.required(entityTemplate, "debris");
            Check.required(decay, "decay");
        }
    }

    @Override
    public String name() {
        return "fly";
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
        return params.from().distanceTo(params.to()) / params.speed();
    }

    @Override
    public Applied apply(Params params, Actor self) {
        return Applied.of(self.entity().withPosition(params.to()));
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        List<EffectCall<?>> calls = new ArrayList<>();
        if (params.hit() != null) {
            calls.add(damage.call(Damage.Params.of(params.hit(), params.damageRoll())));
        }
        calls.add(breakEffect.call(new Break.Params(self.id(), params.p(), params.entityTemplate(), params.decay())));
        return calls;
    }
}
