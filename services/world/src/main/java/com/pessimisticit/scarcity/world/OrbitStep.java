package com.pessimisticit.scarcity.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Moves an entity along the circle {@code center}, {@code radius} by arc length {@code arc}
 * (positive: counter-clockwise, y up). Effect at the end, duration {@code abs(arc) / state.speed}.
 * The new angle is the current angle around the center plus {@code arc / radius}; the position
 * is recomputed from center and radius, so the radius never drifts.
 */
@Component
public class OrbitStep implements ActionType {

    public static final String NAME = "orbit-step";

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
        return Math.abs(params.path("arc").asDouble()) / state.path("speed").asDouble();
    }

    @Override
    public void apply(JsonNode params, ObjectNode state) {
        double cx = params.path("center").path("x").asDouble();
        double cy = params.path("center").path("y").asDouble();
        double r = params.path("radius").asDouble();
        double arc = params.path("arc").asDouble();
        double angle = Math.atan2(state.path("position").path("y").asDouble() - cy,
                state.path("position").path("x").asDouble() - cx) + arc / r;
        ObjectNode position = state.putObject("position");
        position.put("x", cx + r * Math.cos(angle));
        position.put("y", cy + r * Math.sin(angle));
    }
}
