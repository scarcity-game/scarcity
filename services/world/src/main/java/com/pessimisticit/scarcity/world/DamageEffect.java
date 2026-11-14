package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * damage: params {target, mean, sd, min, max}. Applies damage to an entity.
 */
@Component
public class DamageEffect implements EffectType {

    public static final String NAME = "damage";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(JsonNode params, EffectContext ctx) {
        String target = params.path("target").asText();
        JsonNode cur = ctx.exists(target) ? ctx.peek(target) : null;
        if (cur == null || !cur.path("hp").isNumber()) {
            ctx.result(JsonNodeFactory.instance.objectNode().put("skipped", "no hp"));
            return;
        }

        double value = Draws.truncatedGaussian(ctx, "damage",
            params.path("mean").asDouble(),
            params.path("sd").asDouble(),
            params.path("min").asDouble(),
            params.path("max").asDouble());

        ObjectNode s = ctx.state(target);
        double before = s.path("hp").asDouble();
        double after = Math.max(0.0, before - value);
        s.put("hp", after);
        ctx.result(JsonNodeFactory.instance.objectNode()
            .put("hpBefore", before)
            .put("hpAfter", after));
    }
}
