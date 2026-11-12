package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Does nothing for params.duration time units.
 */
@Component
public class Wait implements ActionType {

    public static final String NAME = "wait";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Effect effect() {
        return Effect.NONE;
    }

    @Override
    public double duration(JsonNode params, ObjectNode state) {
        return params.path("duration").asDouble();
    }

    @Override
    public void apply(JsonNode params, ObjectNode state) {
        // no-op
    }
}
