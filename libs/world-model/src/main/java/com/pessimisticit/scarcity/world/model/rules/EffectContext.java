package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/**
 * What an effect may do (docs/actions.md, "Effects"). The view
 * ({@link AreaView}) shows every change made so far in the advance. Started
 * actions and triggered effects run right after this effect's log entry,
 * depth first, in call order.
 */
public interface EffectContext extends AreaView {

    /** The game time of the event that set this effect off. */
    double time();

    /** The area's random generator. */
    RandomGenerator random();

    /** Replaces an entity's state with {@code change} of it; the entity is written once at the end of the advance. */
    void update(String id, UnaryOperator<EntityState> change);

    /** Creates an entity in {@code parentId} (the area) and returns its new id. */
    String create(String parentId, EntityState state);

    /** Destroys an entity; an action it has in progress is cancelled and never ends. */
    void destroy(String id);

    /** Starts an action on an entity (cause: this effect), under the catalogue's start rules. */
    void startAction(String id, ActionSpec action);

    /** Triggers another effect (cause: this effect). */
    void trigger(EffectCall<?> call);

    /** Sets the log entry's {@code result}. */
    void result(Record result);

    /** Adds {@code draw} to the log entry's {@code draws} with {@code "for": forWhat}, and returns it. */
    <D extends Draw> D draw(String forWhat, D draw);
}
