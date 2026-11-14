package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An action to start: {@code {"type", "params"}}, as in a behaviour's choice
 * or an intent. The params stay JSON here; the action type decodes them.
 */
public record ActionSpec(
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) ObjectNode params) {

    public ActionSpec {
        Check.text(type, "type");
        params = Check.required(params, "params").deepCopy();
    }

    /** A copy: the record stays immutable. */
    @Override
    public ObjectNode params() {
        return params.deepCopy();
    }
}
