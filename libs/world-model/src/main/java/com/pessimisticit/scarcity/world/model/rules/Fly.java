package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;
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

    public static final Fly INSTANCE = new Fly();

    private Fly() {
    }

    /** {@code hit} is null for a line that met the boundary. */
    public record Params(
            @JsonProperty(required = true) Position from,
            @JsonProperty(required = true) Position to,
            @JsonProperty(required = true) double speed,
            String hit,
            @JsonProperty(required = true) DamageRoll damage,
            @JsonProperty(required = true) double p,
            @JsonProperty(required = true) ObjectNode debris,
            @JsonProperty(required = true) Decay.Params decay) {
        public Params {
            Check.required(from, "from");
            Check.required(to, "to");
            Check.positive(speed, "speed");
            Check.required(damage, "damage");
            Check.probability(p, "p");
            debris = Templates.checked(debris, "debris");
            Check.required(decay, "decay");
        }

        /** A copy: the record stays immutable. */
        @Override
        public ObjectNode debris() {
            return debris.deepCopy();
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
    public double duration(Params params, EntityState self) {
        return params.from().distanceTo(params.to()) / params.speed();
    }

    @Override
    public Applied apply(Params params, Actor self) {
        return Applied.of(self.state().withPosition(params.to()));
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        List<EffectCall<?>> calls = new ArrayList<>();
        if (params.hit() != null) {
            calls.add(Damage.INSTANCE.call(Damage.Params.of(params.hit(), params.damage())));
        }
        calls.add(Break.INSTANCE.call(new Break.Params(self.id(), params.p(), params.debris(), params.decay())));
        return calls;
    }
}
