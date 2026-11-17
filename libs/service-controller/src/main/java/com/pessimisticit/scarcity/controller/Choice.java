package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Entity;

/**
 * One thing a controller may decide to do. The {@link Chooser} draws which one by weight; the
 * choice then works out the params from the game state, as a player would.
 */
public interface Choice {

    /** The key of its weight in {@code controller.weights}, and its name in the reported draw. */
    String name();

    /**
     * The action for {@code self} now.
     *
     * @throws Unavailable if it can't be chosen now (its target is gone, say)
     */
    ActionSpec plan(Entity self, World world);

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
