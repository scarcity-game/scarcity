package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Draws;

/**
 * {@code spawn-wait}: draws the {@code interval} of a den's cycle, a truncated gaussian recorded
 * as {@code interval}, and starts the {@code spawn} that waits that long on the den.
 */
public final class SpawnWait implements Effect<SpawnWait.Params> {

    public static final SpawnWait INSTANCE = new SpawnWait();

    private SpawnWait() {
    }

    public record Params(@JsonProperty(required = true) String den, @JsonProperty(required = true) Spawn.Params spawn) {
        public Params {
            Check.text(den, "den");
            Check.required(spawn, "spawn");
        }
    }

    /** The wait drawn, in seconds. */
    public record Result(double interval) {
    }

    @Override
    public String name() {
        return "spawn-wait";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Spawn.Interval interval = params.spawn().interval();
        double wait = ctx.draw("interval",
                Draws.truncatedGaussian(ctx.random(), interval.mean(), interval.sd(), interval.min(), interval.max())).value();
        ctx.result(new Result(wait));
        ctx.startAction(params.den(), Spawn.specFor(params.spawn().waiting(wait)));
    }
}
