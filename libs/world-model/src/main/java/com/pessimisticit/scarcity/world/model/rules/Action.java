package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * An action type (docs/actions.md, "Action types"). The engine decides when
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
    double duration(P params, EntityState self);

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
        return Applied.of(self.state());
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
