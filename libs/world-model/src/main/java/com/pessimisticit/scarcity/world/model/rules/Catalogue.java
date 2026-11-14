package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The action and effect types a world runs with, by name. Immutable. It is
 * the one place a type name is looked up, and {@link #start} is the one
 * place the start rules live (docs/actions.md, "Actions" and "Intents").
 */
public final class Catalogue {

    private final SortedMap<String, Action<?>> actions;
    private final SortedMap<String, Effect<?>> effects;

    private Catalogue(SortedMap<String, Action<?>> actions, SortedMap<String, Effect<?>> effects) {
        this.actions = Collections.unmodifiableSortedMap(actions);
        this.effects = Collections.unmodifiableSortedMap(effects);
    }

    /** Everything in docs/actions.md. */
    public static Catalogue standard() {
        return of(
                List.of(Step.INSTANCE, Wait.INSTANCE, Throw.INSTANCE, Fly.INSTANCE, Decay.INSTANCE),
                List.of(LineAttack.INSTANCE, Create.INSTANCE, Destroy.INSTANCE, Damage.INSTANCE, Break.INSTANCE));
    }

    /** A catalogue of these types; two with one name are an error. */
    public static Catalogue of(Collection<? extends Action<?>> actions, Collection<? extends Effect<?>> effects) {
        return new Catalogue(byName(actions, Action::name), byName(effects, Effect::name));
    }

    private static <T> SortedMap<String, T> byName(Collection<? extends T> types, Function<T, String> name) {
        SortedMap<String, T> map = new TreeMap<>();
        for (T type : types) {
            if (map.put(name.apply(type), type) != null) {
                throw new IllegalArgumentException("two types named '" + name.apply(type) + "'");
            }
        }
        return map;
    }

    public SortedSet<String> actionNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(actions.keySet()));
    }

    public SortedSet<String> effectNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(effects.keySet()));
    }

    public Optional<Action<?>> action(String name) {
        return Optional.ofNullable(actions.get(name));
    }

    public Optional<Effect<?>> effect(String name) {
        return Optional.ofNullable(effects.get(name));
    }

    /**
     * Checks every start rule for {@code spec} on {@code self} and returns
     * the action ready to start: the type is known and in the entity's
     * {@code actions}, the params decode, the type's own
     * {@link Action#validate} passes and the duration is positive and
     * finite. Whether the entity is idle is the engine's check.
     *
     * @throws InvalidAction naming the key that is wrong
     */
    public BoundAction<?> start(ActionSpec spec, Actor self) {
        String type = spec.type();
        Action<?> action = known(type);
        if (!self.state().actions().contains(type)) {
            throw new InvalidAction(type + ": not in the entity's actions " + self.state().actions());
        }
        BoundAction<?> bound = bind(action, spec.params());
        try {
            bound.validate(self);
        } catch (InvalidAction e) {
            throw new InvalidAction(type + ": " + e.getMessage(), e);
        }
        double duration = bound.duration(self.state());
        if (!Double.isFinite(duration) || duration <= 0.0) {
            throw new InvalidAction(type + ": duration: must be positive and finite, got " + duration);
        }
        return bound;
    }

    /**
     * The type and params of an action in progress, to end it. No start
     * rule is checked again.
     *
     * @throws InvalidAction if the type is unknown or the params don't decode
     */
    public BoundAction<?> resume(ActionInProgress action) {
        return bind(known(action.type()), action.params());
    }

    private Action<?> known(String type) {
        Action<?> action = actions.get(type);
        if (action == null) {
            throw new InvalidAction("type: unknown action '" + type + "'");
        }
        return action;
    }

    private static <P extends Record> BoundAction<P> bind(Action<P> action, ObjectNode params) {
        try {
            return new BoundAction<>(action, WorldJson.fromJson(params, action.params(), action.name() + " params"));
        } catch (WorldJsonException e) {
            throw new InvalidAction(e.getMessage(), e);
        }
    }
}
