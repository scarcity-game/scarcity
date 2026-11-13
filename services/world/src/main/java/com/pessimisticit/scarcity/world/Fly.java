package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Moves an entity from one position to another.
 */
@Component
public class Fly implements ActionType {

    public static final String NAME = "fly";

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
        JsonNode from = params.path("from");
        JsonNode to = params.path("to");
        double speed = params.path("speed").asDouble();
        return Math.hypot(to.path("x").asDouble() - from.path("x").asDouble(),
                         to.path("y").asDouble() - from.path("y").asDouble()) / speed;
    }

    @Override
    public void apply(JsonNode params, ObjectNode state) {
        JsonNode to = params.path("to");
        ObjectNode position = state.putObject("position");
        position.put("x", to.path("x").asDouble());
        position.put("y", to.path("y").asDouble());
    }

    @Override
    public java.util.List<EffectCall> onEnd(String self, JsonNode params, ObjectNode state) {
        java.util.List<EffectCall> result = new java.util.ArrayList<>();

        // Add damage effect if hit parameter is present
        if (params.path("hit").isTextual()) {
            ObjectNode damageParams = JsonNodeFactory.instance.objectNode();
            damageParams.put("target", params.path("hit").asText());
            damageParams.put("mean", params.path("damage").path("mean").asDouble());
            damageParams.put("sd", params.path("damage").path("sd").asDouble());
            damageParams.put("min", params.path("damage").path("min").asDouble());
            damageParams.put("max", params.path("damage").path("max").asDouble());
            result.add(new EffectCall("damage", damageParams));
        }

        // Add break effect
        ObjectNode breakParams = JsonNodeFactory.instance.objectNode();
        breakParams.put("target", self);
        breakParams.put("p", params.path("p").asDouble());
        breakParams.set("debris", params.path("debris").deepCopy());
        breakParams.set("decay", params.path("decay").deepCopy());
        result.add(new EffectCall("break", breakParams));

        return result;
    }
}