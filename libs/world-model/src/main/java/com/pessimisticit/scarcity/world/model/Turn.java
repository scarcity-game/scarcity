package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the area's {@code turns} (docs/actions.md, "Game time"): a
 * controlled entity that is idle, so the game waits for its controller.
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
