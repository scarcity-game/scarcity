package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Attributes;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.List;
import java.util.Optional;

/**
 * {@code spawn-make}: the end of a den's cycle. Makes nothing if the den already has {@code cap}
 * mobs alive (entities whose {@code spawner} is the den); otherwise draws one prototype by weight
 * ({@code pick}), varies its {@code radius}, {@code mass}, {@code speed} and attributes
 * ({@link Draws#vary}), finds a free spot on the ring around the den ({@link Placement}) and
 * triggers {@code create} of it, controlled as the params say and with {@code spawner} the den.
 */
public final class SpawnMake implements Effect<SpawnMake.Params> {

    private final Effect<Create.Params> create;

    public SpawnMake(Effect<Create.Params> create) {
        this.create = create;
    }

    public record Params(@JsonProperty(required = true) String den, @JsonProperty(required = true) Spawn.Params spawn) {
        public Params {
            Check.text(den, "den");
            Check.required(spawn, "spawn");
        }
    }

    /** Nothing was made: {@code at cap} or {@code no room}. */
    public record Skipped(String skipped) {
    }

    /** A mob was made, of {@code prototype}, at {@code position}. */
    public record Made(String prototype, Position position) {
    }

    @Override
    public String name() {
        return "spawn-make";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Spawn.Params spawn = params.spawn();
        long alive = ctx.entities().values().stream().filter(e -> params.den().equals(e.spawner())).count();
        if (alive >= spawn.cap()) {
            ctx.result(new Skipped("at cap"));
            return;
        }
        Entity den = ctx.peek(params.den());
        String prototype = pick(spawn.choices(), ctx);
        Entity mob = new EntityTemplate(prototype).at(den.position());
        mob = mob.withRadius(varied(ctx, "radius", mob.radius()));
        Optional<Position> spot = Placement.find(ctx, params.den(), mob);
        if (spot.isEmpty()) {
            ctx.result(new Skipped("no room"));
            return;
        }
        ctx.result(new Made(prototype, spot.get()));
        ctx.trigger(create.call(new Create.Params(ctx.areaId(), made(ctx, mob, params).withPosition(spot.get()), null)));
    }

    private static String pick(List<Spawn.Choice> choices, EffectContext ctx) {
        List<Double> weights = choices.stream().map(Spawn.Choice::weight).toList();
        Draw.Categorical pick = ctx.draw("pick", Draws.categorical(ctx.random(), weights));
        return choices.get(pick.value()).prototype();
    }

    /** The mob with its mass, speed and attributes varied, its controller and its spawner. */
    private static Entity made(EffectContext ctx, Entity base, Params params) {
        Entity mob = base;
        if (mob.mass() != null) {
            mob = mob.withMass(varied(ctx, "mass", mob.mass()));
        }
        if (mob.speed() != null) {
            mob = mob.withSpeed(varied(ctx, "speed", mob.speed()));
        }
        Attributes attributes = mob.attributes();
        if (attributes != null) {
            mob = mob.withAttributes(attributes.vary(ctx.random(), ctx::draw));
        }
        return mob.withBehaviour(null).withController(params.spawn().controller()).withSpawner(params.den());
    }

    private static double varied(EffectContext ctx, String name, double value) {
        return ctx.draw(name, Draws.vary(ctx.random(), value, Draws.VARIATION)).value();
    }
}
