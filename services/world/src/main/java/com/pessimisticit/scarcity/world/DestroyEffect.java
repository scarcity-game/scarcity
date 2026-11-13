package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * destroy: params {target}. Destroys the entity; an action in progress is cancelled.
 */
@Component
public class DestroyEffect implements EffectType {

    public static final String NAME = "destroy";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(JsonNode params, EffectContext ctx) {
        ctx.destroy(params.path("target").asText());
    }
}