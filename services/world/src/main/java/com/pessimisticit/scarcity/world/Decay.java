package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Destroys an entity after a duration.
 */
@Component
public class Decay implements ActionType {

    public static final String NAME = "decay";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Effect effect() {
        return Effect.END;
    }

    @Override
    public double duration(JsonNode params, ObjectNode state) {
        return params.path("duration").asDouble();
    }

    @Override
    public void apply(JsonNode params, ObjectNode state) {
        // no-op
    }

    @Override
    public java.util.List<EffectCall> onEnd(String self, JsonNode params, ObjectNode state) {
        return java.util.List.of(new EffectCall("destroy", JsonNodeFactory.instance.objectNode().put("target", self)));
    }
}