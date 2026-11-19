package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Behaviour;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Placeholder;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * {@code arrive}: the placeholder's action, taking the area's warm-up
 * {@code params.duration}; at its end the {@code appear} effect puts the
 * entity in the area (docs/actions.md, "Crossing areas").
 */
public final class Arrive implements Action<Arrive.Params> {

    public static final String NAME = "arrive";

    /** The longest warm-up or catch-up, in time units. */
    public static final double WARM_UP = 500.0;

    private final Effect<Appear.Params> appear;

    public Arrive(Effect<Appear.Params> appear) {
        this.appear = appear;
    }

    public record Params(@JsonProperty(required = true) double duration) {
        public Params {
            Check.positive(duration, "duration");
        }
    }

    /**
     * How long an area at {@code areaTime} runs before the game's {@code gameTime}: all of the
     * gap, but at most {@link #WARM_UP}.
     */
    public static double warmUp(double areaTime, double gameTime) {
        return Math.min(WARM_UP, gameTime - areaTime);
    }

    /** The placeholder of {@code traveller} at {@code at}, arriving after {@code duration}; it starts when the area next advances. */
    public static Entity placeholder(Entity traveller, Position at, double duration) {
        ActionSpec spec = new ActionSpec(NAME, WorldJson.toObject(new Params(duration)));
        return new Placeholder("arriving " + traveller.name(), traveller)
                .withPosition(at)
                .withActions(List.of(NAME))
                .withBehaviour(new Behaviour.Repeat(spec));
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
        return params.duration();
    }

    @Override
    public List<EffectCall<?>> onEnd(Params params, Actor self) {
        return List.of(appear.call(new Appear.Params(self.id())));
    }
}
