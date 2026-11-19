package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * {@code throw}: at its start, a {@code line-attack} from the entity's
 * position at the point {@code params.at}; the duration is the recovery.
 * Aiming is the controller's choice; the scatter is drawn here.
 */
public final class Throw implements Action<Throw.Params> {

    public static final String NAME = "throw";

    private final Effect<LineAttack.Params> lineAttack;

    public Throw(Effect<LineAttack.Params> lineAttack) {
        this.lineAttack = lineAttack;
    }

    /** The spec of a throw, which needs no collaborators. */
    public static ActionSpec specFor(Params params) {
        return new ActionSpec(NAME, WorldJson.toObject(params));
    }

    public record Params(
            @JsonProperty(required = true) Position at,
            @JsonProperty(required = true) double spread,
            @JsonProperty(required = true) double recovery,
            @JsonProperty(required = true) Projectile projectile) {
        public Params {
            Check.required(at, "at");
            Check.nonNegative(spread, "spread");
            Check.positive(recovery, "recovery");
            Check.required(projectile, "projectile");
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
        return Timing.START;
    }

    @Override
    public double duration(Params params, Entity self) {
        return params.recovery();
    }

    @Override
    public void validate(Params params, Actor self) {
        if (self.entity().position().equals(params.at())) {
            throw new InvalidAction("at: must differ from the entity's position " + params.at());
        }
    }

    @Override
    public List<EffectCall<?>> onStart(Params params, Actor self) {
        return List.of(lineAttack.call(new LineAttack.Params(
                self.id(), self.entity().position(), params.at(), params.spread(), params.projectile())));
    }
}
