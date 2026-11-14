package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * create: params {parent, state, start?}. Creates an entity with state in parent (the area). If start {type, params} is given, starts that action on it at the effect's game time.
 */
@Component
public class CreateEffect implements EffectType {

    public static final String NAME = "create";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(JsonNode params, EffectContext ctx) {
        String parent = params.path("parent").asText();
        JsonNode state = params.path("state");
        if (!state.isObject()) {
            throw new IllegalArgumentException("create needs a state object");
        }
        String id = ctx.create(parent, (ObjectNode) state);
        JsonNode start = params.path("start");
        if (start.isObject()) {
            ctx.startAction(id, start.path("type").asText(), start.path("params"));
        }
    }
}
