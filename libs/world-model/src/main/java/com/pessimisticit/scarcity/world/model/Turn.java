package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The area's {@code activeEntity} or one of its {@code pending} (docs/actions.md,
 * "Turns"): a controlled entity that is idle, waiting for its controller.
 *
 * @param since the game time it became idle
 */
public record Turn(
        @JsonProperty(required = true) String entity,
        @JsonProperty(required = true) Controller controller,
        @JsonProperty(required = true) double since) {

    public Turn {
        Check.text(entity, "entity");
        Check.required(controller, "controller");
        Check.nonNegative(since, "since");
    }
}
