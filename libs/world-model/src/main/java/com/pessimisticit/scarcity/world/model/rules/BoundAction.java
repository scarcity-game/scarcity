package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import java.util.Objects;

/**
 * An action type with its decoded params, from {@link Catalogue#start} or
 * {@link Catalogue#resume}. The engine calls it without knowing either type.
 */
public record BoundAction<P extends Record>(Action<P> action, P params) {

    public BoundAction {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(params, "params");
    }

    public String name() {
        return action.name();
    }

    public Timing timing() {
        return action.timing();
    }

    public double duration(EntityState self) {
        return action.duration(params, self);
    }

    public Applied apply(Actor self) {
        return action.apply(params, self);
    }

    public List<EffectCall<?>> onStart(Actor self) {
        return action.onStart(params, self);
    }

    public List<EffectCall<?>> onEnd(Actor self) {
        return action.onEnd(params, self);
    }

    public ObjectNode paramsJson() {
        return WorldJson.toObject(params);
    }

    /** The {@code action} key of an entity that starts this at {@code start}; {@code seq} and {@code draw} may be null. */
    public ActionInProgress inProgress(EntityState self, double start, Long seq, Draw draw) {
        return new ActionInProgress(name(), paramsJson(), start, start + duration(self), timing(), seq, draw);
    }

    void validate(Actor self) {
        action.validate(params, self);
    }
}
