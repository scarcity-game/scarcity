package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.engine.Intent;

/**
 * The body of {@code POST /api/world/intents} (docs/actions.md, "Intents").
 *
 * @param ifVersion the entity version the choice was made on; null: any
 * @param reported the controller's free-form explanation; null: none
 */
public record IntentRequest(
        @JsonProperty(required = true) String entity,
        @JsonProperty(required = true) ActionSpec actionSpec,
        Long ifVersion,
        JsonNode reported) {

    public IntentRequest {
        Check.text(entity, "entity");
        Check.required(actionSpec, "actionSpec");
        reported = reported == null ? null : reported.deepCopy();
    }

    @Override
    public JsonNode reported() {
        return reported == null ? null : reported.deepCopy();
    }

    Intent by(Controller caller) {
        return new Intent(entity, actionSpec, caller, ifVersion, reported);
    }
}
