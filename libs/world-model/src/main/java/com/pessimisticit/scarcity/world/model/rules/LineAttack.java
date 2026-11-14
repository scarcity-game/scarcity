package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draws;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Position;

/**
 * {@code line-attack}: aims from {@code from} at the point {@code at}, adds
 * a gaussian scatter (sd {@code spread}, radians) and follows the line to
 * the nearest entity with a radius other than {@code source}, else the
 * boundary. Then creates the projectile at {@code from}, flying to where the
 * line stopped.
 */
public final class LineAttack implements Effect<LineAttack.Params> {

    public static final LineAttack INSTANCE = new LineAttack();

    private LineAttack() {
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

    /** {@code hit} is the entity met, or null (written as null) when the line met the boundary. */
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
        if (!(hit.t() > 0.0 && Double.isFinite(hit.t()))) {
            throw new IllegalArgumentException("line-attack: no line from " + from + " at " + direction);
        }
        Position point = RayCast.along(from, dx, dy, hit.t());
        ctx.result(new Result(direction, hit.entity(), point));
        ctx.trigger(Create.INSTANCE.call(projectile(ctx.areaId(), params.projectile(), from, point, hit.entity())));
    }

    private static Create.Params projectile(String area, Projectile p, Position from, Position to, String hit) {
        EntityState state = Templates.at(p.state(), from);
        Fly.Params flight = new Fly.Params(from, to, p.speed(), hit, p.damage(), p.p(), p.debris(), p.decay());
        return new Create.Params(area, state, Fly.INSTANCE.spec(flight));
    }
}
