package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Decides, inside an advance, the turns of entities whose controller is an intelligence service
 * (docs/actions.md, "Game time" and "Intelligence services"): the advance goes on past them, and
 * stops only for a turn no decider takes (a player's, an npc service's) or one it can't decide now.
 * The engine stays pure: the caller's decider may call out (world asks the service over HTTP).
 */
public interface Decider {

    /** Decides nothing: every advance stops at the first idle controlled entity. */
    Decider NONE = new Decider() {
        @Override
        public boolean decides(Controller controller) {
            return false;
        }

        @Override
        public Optional<Decision> decide(AreaView areaView, Turn turn) {
            return Optional.empty();
        }
    };

    /** Whether this controller's turns are decided within the advance. */
    boolean decides(Controller controller);

    /**
     * The action for {@code turn}, the area's active entity, in {@code areaView} as the advance has
     * it now. Empty: no decision now (the service is down, the advance has run long enough); the
     * advance stops with the turn up, and the caller decides it later.
     */
    Optional<Decision> decide(AreaView areaView, Turn turn);

    /** A decided turn: the action and the draws behind it ({@code reported}, null for none), as an intent's. */
    record Decision(ActionSpec actionSpec, JsonNode reported) {
        public Decision {
            Objects.requireNonNull(actionSpec, "actionSpec");
            reported = reported == null ? null : reported.deepCopy();
        }

        @Override
        public JsonNode reported() {
            return reported == null ? null : reported.deepCopy();
        }
    }
}
