package com.pessimisticit.scarcity.world;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An action type (contract: {@code docs/resources.md}, "Action types"). The engine in
 * {@link Ticker} decides when an action starts and ends; the type decides how long it
 * takes, when its effect happens and what the effect is.
 */
public interface ActionType {

    /** When the effect changes the state; written to the action's {@code effect} key in lower case. NONE means the engine applies nothing at start or end. */
    enum Effect {
        START, END, NONE;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The name used in an entity's {@code actions} and in {@code action.type}. */
    String name();

    Effect effect();

    /** Duration in time units, computed from the params and the entity state when the action starts. */
    double duration(JsonNode params, ObjectNode state);

    /** Applies the effect to {@code state} in place. */
    void apply(JsonNode params, ObjectNode state);
}
