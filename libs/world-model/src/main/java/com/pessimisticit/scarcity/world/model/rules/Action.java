package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import java.util.Optional;

/**
 * An action type (docs/action-types.md, "Action types"). The engine decides when
 * an action starts and ends; the type decides how long it takes, when its
 * effect happens and what it is. Implementations are stateless and found by
 * {@link #name()} in the {@link Catalogue}, never by comparing names.
 *
 * @param <P> the params record, decoded strictly by {@link WorldJson}
 */
public interface Action<P extends Record> {

    /** The name in an entity's {@code actions} and in {@code action.type}. */
    String name();

    /** The params record; JSON that doesn't decode into it can't start. */
    Class<P> params();

    /** When the action changes the state; copied to {@code action.effect}. */
    Timing timing();

    /** The duration, fixed when the action starts. The catalogue rejects one that isn't positive and finite. */
    double duration(P params, Entity self);

    /**
     * The start rules beyond decoding (a step's {@code to} inside the area,
     * say). The message names the key; the catalogue adds the action's name.
     *
     * @throws InvalidAction if the action can't start
     */
    default void validate(P params, Actor self) {
    }

    /** The action's change to its own entity, at the moment {@link #timing()} says. Pure. */
    default Applied apply(P params, Actor self) {
        return Applied.of(self.entity());
    }

    /**
     * A {@link Timing#CHANNEL} action's change over {@code dt} more seconds of
     * it, from its state now (docs/actions.md, "Actions"). Pure. The engine
     * calls it before every event while the action runs; at the end it calls
     * {@link #apply}, which does the rest.
     */
    default Progress progress(P params, Actor self, double dt) {
        throw new UnsupportedOperationException(name() + " is not a channel");
    }

    /**
     * Where a channel is heading now, for the contact forecast: none (the default) for an action
     * that moves nothing. Pure.
     */
    default Optional<Motion> motion(P params, Actor self) {
        return Optional.empty();
    }

    /**
     * Whether an interruption ends the action (docs/actions.md, "Interruption").
     * A {@link Timing#START} action is committed: it has applied its effect
     * and only its recovery is left, which nothing interrupts.
     */
    default boolean interruptible() {
        return timing() != Timing.START;
    }

    /**
     * The action's change to its own entity when it is interrupted now: none
     * by default (a channel stays where it has got to, an {@code end} action
     * never applies its effect). Pure. The log entry's {@code result} is the
     * returned {@link Applied#result()}.
     */
    default Applied interrupt(P params, Actor self) {
        return Applied.of(self.entity());
    }

    /** Effects set off when the action starts, after its log entry. */
    default List<EffectCall<?>> onStart(P params, Actor self) {
        return List.of();
    }

    /** Effects set off when the action ends, after its log entry; {@code self} is the state after {@link #apply}. */
    default List<EffectCall<?>> onEnd(P params, Actor self) {
        return List.of();
    }

    /** This action with {@code params}, as an effect or a behaviour names it. */
    default ActionSpec spec(P params) {
        return new ActionSpec(name(), WorldJson.toObject(params));
    }
}
