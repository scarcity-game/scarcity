package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A point in an area: x to the right, y up, origin bottom-left. */
public record Position(
        @JsonProperty(required = true) double x,
        @JsonProperty(required = true) double y) {

    public Position {
        Check.finite(x, "x");
        Check.finite(y, "y");
    }

    public double distanceTo(Position other) {
        return Math.hypot(other.x - x, other.y - y);
    }
}
