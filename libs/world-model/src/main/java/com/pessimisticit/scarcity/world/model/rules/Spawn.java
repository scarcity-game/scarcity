package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * {@code spawn}: a den's action, one cycle of waiting and then making a mob. The wait is a
 * recorded draw, which a pure {@link #duration} can't make, so a cycle is two actions of this
 * type. The den's behaviour starts the first, with no {@code waited}: it lasts {@link #ARM} and its
 * start triggers {@code spawn-wait}, which draws the {@code interval} and starts the second, the
 * same params with {@code waited} set, interrupting the first at once. The second lasts
 * {@code waited}, and its end triggers {@code spawn-make}. The behaviour then starts the next cycle.
 */
public final class Spawn implements Action<Spawn.Params> {

    public static final String NAME = "spawn";

    /** The duration of the arming action, which is interrupted at the instant it starts. */
    public static final double ARM = 1e-6;

    private final Effect<SpawnWait.Params> wait;
    private final Effect<SpawnMake.Params> make;

    public Spawn(Effect<SpawnWait.Params> wait, Effect<SpawnMake.Params> make) {
        this.wait = wait;
        this.make = make;
    }

    /** The spec of a {@code spawn}, which needs no collaborators. */
    public static ActionSpec specFor(Params params) {
        return new ActionSpec(NAME, WorldJson.toObject(params));
    }

    /** One thing the den may make, drawn with probability {@code weight} / the sum of the weights. */
    public record Choice(@JsonProperty(required = true) double weight, @JsonProperty(required = true) String prototype) {
        public Choice {
            Check.positive(weight, "weight");
            new EntityTemplate(prototype);
        }
    }

    /** The wait: a gaussian {@code mean}, {@code sd}, truncated to [{@code min}, {@code max}), in seconds, so {@code min} > 0. */
    public record Interval(
            @JsonProperty(required = true) double mean,
            @JsonProperty(required = true) double sd,
            @JsonProperty(required = true) double min,
            @JsonProperty(required = true) double max) {
        public Interval {
            Check.finite(mean, "mean");
            Check.positive(sd, "sd");
            Check.positive(min, "min");
            new Draw.Truncate(min, max);
        }
    }

    /** {@code waited} is the drawn wait, set only on the second action of a cycle. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Params(
            @JsonProperty(required = true) List<Choice> choices,
            @JsonProperty(required = true) int cap,
            @JsonProperty(required = true) Controller controller,
            @JsonProperty(required = true) Interval interval,
            Double waited) {
        public Params {
            Check.required(choices, "choices");
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("choices: at least one is needed");
            }
            choices = List.copyOf(choices);
            if (cap < 1) {
                throw new IllegalArgumentException("cap: must be at least 1, got " + cap);
            }
            Check.required(controller, "controller");
            Check.required(interval, "interval");
            waited = Check.optionalPositive(waited, "waited");
        }

        /** The same params as the waiting action of a cycle. */
        public Params waiting(double wait) {
            return new Params(choices, cap, controller, interval, wait);
        }
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
    public Timing timing() {
        return Timing.END;
    }

    @Override
    public double duration(Params params, Entity self) {
        return params.waited() == null ? ARM : params.waited();
    }

    @Override
    public List<EffectCall<?>> onStart(Params params, Actor self) {
        return params.waited() == null ? List.of(wait.call(new SpawnWait.Params(self.id(), params))) : List.of();
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return params.waited() == null ? List.of() : List.of(make.call(new SpawnMake.Params(self.id(), params)));
    }
}
