package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;

/** A step an effect set off, run depth first after the effect's entry. */
sealed interface Work {

    /**
     * Where the step sits: the event's game time, the entry that caused it,
     * its root, and the {@code source}: the entity whose own action set the
     * chain off, which an effect in it does not interrupt.
     */
    record Origin(double time, long cause, long root, String source) {
    }

    void runOn(Advance advance);

    /** Run an effect. */
    record RunEffect(EffectCall<?> call, Origin origin) implements Work {
        @Override
        public void runOn(Advance advance) {
            advance.runEffect(this);
        }
    }

    /** Start an action on an entity. */
    record StartAction(String entity, ActionSpec actionSpec, Origin origin) implements Work {
        @Override
        public void runOn(Advance advance) {
            advance.startFromEffect(this);
        }
    }
}
