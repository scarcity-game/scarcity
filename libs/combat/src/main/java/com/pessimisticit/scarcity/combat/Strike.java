package com.pessimisticit.scarcity.combat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Action;
import com.pessimisticit.scarcity.world.model.rules.Actor;
import com.pessimisticit.scarcity.world.model.rules.Effect;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import java.util.List;

/**
 * {@code strike}: an unarmed blow at {@code target}, which must be within the striker's reach, edge
 * to edge (centre distance minus both radii). At its start it sets off {@code melee-attack}, which
 * draws the hit and the damage; the duration is the striker's {@code strikeTime}.
 */
public final class Strike implements Action<Strike.Params> {

    public static final String NAME = "strike";

    /** A strike's duration when the striker's prototype sets no {@code strikeTime}, in seconds. */
    public static final double DEFAULT_TIME = 1.0;

    private final Effect<MeleeAttack.Params> meleeAttack;

    public Strike(Effect<MeleeAttack.Params> meleeAttack) {
        this.meleeAttack = meleeAttack;
    }

    /** The spec of a strike at {@code target}, which needs no collaborators. */
    public static ActionSpec specFor(String target) {
        return new ActionSpec(NAME, WorldJson.toObject(new Params(target)));
    }

    public record Params(@JsonProperty(required = true) String target) {
        public Params {
            Check.text(target, "target");
        }
    }

    /** How far {@code striker} reaches, edge to edge: its {@code reach}, else its radius. */
    public static double reach(Entity striker) {
        return striker.reach() != null ? striker.reach() : striker.radius();
    }

    /** The gap between two circles' edges: centre distance minus both radii; negative when they overlap. */
    public static double gap(Entity a, Entity b) {
        return a.position().distanceTo(b.position()) - a.radius() - b.radius();
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
        return self.strikeTime() != null ? self.strikeTime() : DEFAULT_TIME;
    }

    @Override
    public void validate(Params params, Actor self) {
        Entity striker = self.entity();
        if (striker.attributes() == null || striker.radius() == null) {
            throw new InvalidAction("actions: " + self.id() + " has no attributes or no radius to strike with");
        }
        if (params.target().equals(self.id())) {
            throw new InvalidAction("target: an entity can't strike itself");
        }
        if (!self.areaView().exists(params.target())) {
            throw new InvalidAction("target: no entity " + params.target() + " in the area");
        }
        Entity target = self.areaView().peek(params.target());
        if (target.radius() == null) {
            throw new InvalidAction("target: " + params.target() + " has no radius; only a circle can be struck");
        }
        double gap = gap(striker, target);
        if (gap > reach(striker)) {
            throw new InvalidAction("target: " + params.target() + " is " + gap + " m away edge to edge, beyond reach "
                    + reach(striker) + " m");
        }
    }

    @Override
    public List<EffectCall<?>> onStart(Params params, Actor self) {
        return List.of(meleeAttack.call(new MeleeAttack.Params(self.id(), params.target())));
    }
}
