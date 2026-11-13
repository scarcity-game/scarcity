package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Throws a projectile with recovery time.
 */
@Component
public class Throw implements ActionType {

    public static final String NAME = "throw";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Effect effect() {
        return Effect.START;
    }

    @Override
    public double duration(JsonNode params, ObjectNode state) {
        return params.path("recovery").asDouble();
    }

    @Override
    public void apply(JsonNode params, ObjectNode state) {
        // no-op
    }

    @Override
    public java.util.List<EffectCall> onStart(String self, JsonNode params, ObjectNode state) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("source", self);
        p.set("from", state.path("position").deepCopy());
        p.set("target", params.path("target").deepCopy());
        p.set("spread", params.path("spread").deepCopy());
        p.set("projectile", params.path("projectile").deepCopy());
        return java.util.List.of(new EffectCall("line-attack", p));
    }
}