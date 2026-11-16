package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import java.util.Objects;

/**
 * A controller's choice of the next action for an entity (docs/actions.md,
 * "Intents"). Whether {@code by} may control the entity is the caller's
 * check (403); the engine logs it.
 *
 * @param ifVersion the entity version the choice was made on; null: any
 * @param reported the controller's free-form explanation, logged verbatim; null: none
 */
public record Intent(String entity, ActionSpec actionSpec, Controller controller, Long ifVersion, JsonNode reported) {

    public Intent {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(actionSpec, "action");
        Objects.requireNonNull(controller, "by");
        reported = reported == null ? null : reported.deepCopy();
    }

    public static Intent of(String entity, ActionSpec actionSpec, Controller controller) {
        return new Intent(entity, actionSpec, controller, null, null);
    }

    @Override
    public JsonNode reported() {
        return reported == null ? null : reported.deepCopy();
    }
}
