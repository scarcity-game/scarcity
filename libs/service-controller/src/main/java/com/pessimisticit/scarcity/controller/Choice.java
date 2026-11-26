package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One thing a controller may decide to do. Every turn, {@link Player} asks each choice the entity
 * may take for a plan first, and the {@link Chooser} draws by weight only among the choices whose
 * plan is feasible: one that world should accept now. A choice works out its params from the
 * game state, as a player would.
 */
public interface Choice {

    /** The key of its weight in {@code controller.weights}, and its name in the reported draw. */
    String name();

    /**
     * What it would do for the entity now: a feasible plan, or {@link Plan#none} with why not
     * (the stairs are out of reach, no clear step was found). Either way, with every draw made
     * while planning. A choice found infeasible is left out of this turn's draw only; it is asked
     * again on the next turn.
     */
    Plan plan(Situation situation);

    /**
     * The turn as the entity sees it: the turn, the entity as world holds it now, every entity in
     * its area (read once per turn, the entity itself included as it is now). There is nothing
     * else: in endpoint mode a service never calls world, so a choice works from this alone.
     */
    record Situation(TurnFrame turnFrame, Entity entity, AreaView areaView) {
    }

    /**
     * What a choice decided: the action ({@code null}: not feasible now, and {@code reason} says
     * why), and the draws it made to plan it, by what each was for and in the order made. They go
     * into the intent's {@code reported}, so the log shows every random outcome.
     */
    record Plan(ActionSpec actionSpec, Map<String, Draw> draws, String reason) {
        public Plan {
            draws = Collections.unmodifiableMap(new LinkedHashMap<>(draws));
            if (actionSpec == null) {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** A feasible plan with no draws. */
        public static Plan of(ActionSpec actionSpec) {
            return of(actionSpec, Map.of());
        }

        /** A feasible plan. */
        public static Plan of(ActionSpec actionSpec, Map<String, Draw> draws) {
            return new Plan(Objects.requireNonNull(actionSpec, "actionSpec"), draws, null);
        }

        /** Not feasible now, with no draws. */
        public static Plan none(String reason) {
            return none(reason, Map.of());
        }

        /** Not feasible now, after the draws made while looking. */
        public static Plan none(String reason, Map<String, Draw> draws) {
            return new Plan(null, draws, reason);
        }

        /** Whether world should accept it now. */
        public boolean feasible() {
            return actionSpec != null;
        }
    }
}
