package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * break: params {target, p, debris, decay}. Breaks an entity with a probability,
 * creating debris and optionally starting a decay action.
 */
@Component
public class BreakEffect implements EffectType {

    public static final String NAME = "break";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(JsonNode params, EffectContext ctx) {
        String target = params.path("target").asText();
        if (!ctx.exists(target)) {
            return;
        }

        boolean broke = Draws.bernoulli(ctx, "break", params.path("p").asDouble());

        if (broke) {
            // Create debris
            ObjectNode debris = (ObjectNode) params.path("debris").deepCopy();
            debris.set("position", ctx.peek(target).path("position").deepCopy());

            // Build destroy parameters
            ObjectNode destroyParams = JsonNodeFactory.instance.objectNode();
            destroyParams.put("target", target);

            // Build create parameters
            ObjectNode createParams = JsonNodeFactory.instance.objectNode();
            createParams.put("parent", ctx.areaId());
            createParams.set("state", debris);

            ObjectNode startParams = (ObjectNode) params.path("decay").deepCopy();
            ObjectNode startActionParams = JsonNodeFactory.instance.objectNode();
            startActionParams.put("type", "decay");
            startActionParams.set("params", startParams);
            createParams.set("start", startActionParams);

            // First trigger destroy, then create
            ctx.trigger("destroy", destroyParams);
            ctx.trigger("create", createParams);
        } else {
            // Start decay action on the target
            ctx.startAction(target, "decay", params.path("decay").deepCopy());
        }
    }
}
