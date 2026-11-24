package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;

/**
 * {@code line-attack}: aims from {@code from} at the point {@code at}, adds
 * a gaussian scatter (sd {@code spread}, radians) and follows the line to
 * the nearest obstacle other than {@code source} (an entity with a shape
 * that doesn't overlap; in an enclosed area there is always one, a wall if
 * nothing nearer). Then creates the projectile at {@code from}, flying to where the
 * line stopped.
 */
public final class LineAttack implements Effect<LineAttack.Params> {

    private final Effect<Create.Params> create;
    private final Effect<Damage.Params> damage;
    private final Action<Fly.Params> fly;

    public LineAttack(Effect<Create.Params> create, Effect<Damage.Params> damage, Action<Fly.Params> fly) {
        this.create = create;
        this.damage = damage;
        this.fly = fly;
    }

    public record Params(
            @JsonProperty(required = true) String source,
            @JsonProperty(required = true) Position from,
            @JsonProperty(required = true) Position at,
            @JsonProperty(required = true) double spread,
            @JsonProperty(required = true) Projectile projectile) {
        public Params {
            Check.text(source, "source");
            Check.required(from, "from");
            Check.required(at, "at");
            if (at.equals(from)) {
                throw new IllegalArgumentException("at: must differ from 'from' " + from);
            }
            Check.nonNegative(spread, "spread");
            Check.required(projectile, "projectile");
        }
    }

    /** {@code hit} is the entity met. */
    public record Result(double direction, @JsonInclude(JsonInclude.Include.ALWAYS) String hit, Position point) {
    }

    @Override
    public String name() {
        return "line-attack";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Position from = params.from();
        double aim = Math.atan2(params.at().y() - from.y(), params.at().x() - from.x());
        double direction = aim + ctx.draw("scatter", Draws.gaussian(ctx.random(), 0.0, params.spread())).value();
        double dx = Math.cos(direction);
        double dy = Math.sin(direction);
        RayCast.Hit hit = RayCast.first(ctx, from, dx, dy, 0.0, params.source());
        if (!(hit.t() >= 0.0 && Double.isFinite(hit.t()))) {
            throw new IllegalArgumentException("line-attack: no line from " + from + " at " + direction);
        }
        if (hit.t() == 0.0) {
            contact(params, ctx, direction, hit.entity());
            return;
        }
        Position point = RayCast.along(from, dx, dy, hit.t());
        ctx.result(new Result(direction, hit.entity(), point));
        Entity state = varied(params.projectile().state().at(from), ctx);
        ctx.trigger(create.call(projectile(ctx.areaId(), params.projectile(), state, from, point, hit.entity())));
    }

    /**
     * The line is zero long: the thrower touches what it aims into. A
     * zero-length flight has no duration, so no projectile is created. An
     * entity touched is hit at once ({@code damage}, no {@code break}: there
     * is no projectile to break).
     */
    private void contact(Params params, EffectContext ctx, double direction, String hit) {
        ctx.result(new Result(direction, hit, params.from()));
        ctx.trigger(damage.call(Damage.Params.of(hit, params.projectile().damageRoll())));
    }

    /** The projectile's {@code radius} and {@code mass}, each varied by a recorded draw ({@link Draws#vary}). */
    private static Entity varied(Entity state, EffectContext ctx) {
        double radius = ctx.draw("radius", Draws.vary(ctx.random(), state.radius(), Draws.VARIATION)).value();
        double mass = ctx.draw("mass", Draws.vary(ctx.random(), state.mass(), Draws.VARIATION)).value();
        return state.withRadius(radius).withMass(mass);
    }

    private Create.Params projectile(String area, Projectile p, Entity state, Position from, Position to,
            String hit) {
        Fly.Params flight = new Fly.Params(from, to, p.speed(), hit, p.damageRoll(), p.p(), p.debris(), p.decay());
        return new Create.Params(area, state, fly.spec(flight));
    }
}
