package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import java.util.Map;

/**
 * One thing a controller may decide to do. The {@link Chooser} draws which one by weight; the
 * choice then works out the params from the game state, as a player would.
 */
public interface Choice {

    /** The key of its weight in {@code controller.weights}, and its name in the reported draw. */
    String name();

    /**
     * The action for {@code self} now, in the turn's area.
     *
     * @throws Unavailable if it can't be chosen now (its target is gone, say)
     */
    Plan plan(TurnFrame turn, Entity self, World world);

    /**
     * What a choice decided: the action, and the draws it made to plan it, by what each was for.
     * They go into the intent's {@code reported.plan}, so the log shows every random outcome.
     */
    record Plan(ActionSpec actionSpec, Map<String, Draw> draws) {
        public Plan {
            draws = Map.copyOf(draws);
        }

        /** A plan with no draws. */
        public static Plan of(ActionSpec actionSpec) {
            return new Plan(actionSpec, Map.of());
        }
    }

    /** The choice can't be made now; the controller skips it for a while (as a 422). */
    final class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Unavailable(String reason) {
            super(reason);
        }

        public Unavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
