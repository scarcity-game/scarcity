package com.pessimisticit.scarcity.world;

import java.lang.Math;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * line-attack: fires a projectile along a line from source to target, with optional spread.
 * Params (all required): source (entity id string), from {x,y} (object of two numbers),
 * target (entity id string), spread (number, radians, >= 0), projectile (object, see below).
 * Projectile object has: state (object), speed (number), damage (object {mean, sd, min, max}),
 * p (number), debris (object), decay (object).
 */
@Component
public class LineAttack implements EffectType {

    public static final String NAME = "line-attack";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(JsonNode params, EffectContext ctx) {
        // Get parameters
        String source = params.path("source").asText();
        JsonNode from = params.path("from");
        double fx = from.path("x").asDouble();
        double fy = from.path("y").asDouble();
        String target = params.path("target").asText();
        double spread = params.path("spread").asDouble();

        // Get target position
        JsonNode tpos = ctx.peek(target).path("position");
        double tx = tpos.path("x").asDouble();
        double ty = tpos.path("y").asDouble();

        // Calculate base direction
        double base = Math.atan2(ty - fy, tx - fx);

        // Apply spread
        double scatter = Draws.gaussian(ctx, "scatter", 0.0, spread);
        double direction = base + scatter;
        double dx = Math.cos(direction);
        double dy = Math.sin(direction);

        // Find nearest hit among entities
        String hitId = null;
        double tHit = Double.POSITIVE_INFINITY;

        for (var entry : ctx.entities().entrySet()) {
            String id = entry.getKey();
            JsonNode entity = entry.getValue();

            // Skip source entity
            if (id.equals(source)) {
                continue;
            }

            // Skip entities without position or radius
            JsonNode position = entity.path("position");
            JsonNode radius = entity.path("radius");
            if (!radius.isNumber() || !position.isObject()) {
                continue;
            }

            double cx = position.path("x").asDouble();
            double cy = position.path("y").asDouble();
            double r = radius.asDouble();

            // Calculate intersection with circle
            double ox = fx - cx;
            double oy = fy - cy;
            double b = dx * ox + dy * oy;
            double c = ox * ox + oy * oy - r * r;
            double disc = b * b - c;

            if (disc < 0) {
                continue;
            }

            double t = -b - Math.sqrt(disc);
            if (t < 0) {
                continue;
            }

            // Keep the entity with the smallest t (strictly smaller replaces, so ties keep the lower id)
            if (t < tHit) {
                tHit = t;
                hitId = id;
            }
        }

        // Calculate boundary intersection
        JsonNode area = ctx.peek(ctx.areaId());
        double w = area.path("width").asDouble();
        double h = area.path("height").asDouble();

        double tb = Double.POSITIVE_INFINITY;
        if (dx > 0) {
            tb = Math.min(tb, (w - fx) / dx);
        }
        if (dx < 0) {
            tb = Math.min(tb, (0 - fx) / dx);
        }
        if (dy > 0) {
            tb = Math.min(tb, (h - fy) / dy);
        }
        if (dy < 0) {
            tb = Math.min(tb, (0 - fy) / dy);
        }

        // Determine final hit
        if (hitId != null && tHit <= tb) {
            // Hit entity
        } else {
            // Hit boundary
            hitId = null;
            tHit = tb;
        }

        // Calculate point of impact
        double px = fx + tHit * dx;
        double py = fy + tHit * dy;

        // Validate the line
        if (!Double.isFinite(tHit) || tHit <= 0) {
            throw new IllegalArgumentException("line-attack: no line from " + from);
        }

        // Set result
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("direction", direction);
        if (hitId != null) {
            result.put("hit", hitId);
        } else {
            result.putNull("hit");
        }
        ObjectNode point = result.putObject("point");
        point.put("x", px);
        point.put("y", py);
        ctx.result(result);

        // Create projectile if specified
        JsonNode projectile = params.path("projectile");
        if (projectile.isObject()) {
            // Build create params
            ObjectNode createParams = JsonNodeFactory.instance.objectNode();
            createParams.put("parent", ctx.areaId());

            // Deep copy projectile.state with added position
            JsonNode projectileState = projectile.path("state");
            ObjectNode stateCopy = (ObjectNode) projectileState.deepCopy();
            ObjectNode positionNode = JsonNodeFactory.instance.objectNode();
            positionNode.put("x", fx);
            positionNode.put("y", fy);
            stateCopy.set("position", positionNode);

            createParams.set("state", stateCopy);

            // Build start params
            ObjectNode startParams = JsonNodeFactory.instance.objectNode();
            startParams.put("type", "fly");

            ObjectNode flyParams = startParams.putObject("params");
            flyParams.set("from", from);
            ObjectNode to = JsonNodeFactory.instance.objectNode();
            to.put("x", px);
            to.put("y", py);
            flyParams.set("to", to);
            flyParams.put("speed", projectile.path("speed").asDouble());

            // Add hit if there was a hit
            if (hitId != null) {
                flyParams.put("hit", hitId);
            }

            // Copy damage
            JsonNode damage = projectile.path("damage");
            ObjectNode damageCopy = (ObjectNode) damage.deepCopy();
            flyParams.set("damage", damageCopy);

            // Copy other parameters
            flyParams.put("p", projectile.path("p").asDouble());

            // Copy debris
            JsonNode debris = projectile.path("debris");
            if (debris.isObject()) {
                ObjectNode debrisCopy = (ObjectNode) debris.deepCopy();
                flyParams.set("debris", debrisCopy);
            }

            // Copy decay
            JsonNode decay = projectile.path("decay");
            if (decay.isObject()) {
                ObjectNode decayCopy = (ObjectNode) decay.deepCopy();
                flyParams.set("decay", decayCopy);
            }

            createParams.set("start", startParams);
            ctx.trigger("create", createParams);
        }
    }
}
