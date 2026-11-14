package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The state of an {@code area}: a {@code width} by {@code height} room (x in
 * [0, width), y in [0, height)) and its game {@code time} (docs/actions.md,
 * "Game time").
 */
public record AreaState(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) double width,
        @JsonProperty(required = true) double height,
        @JsonProperty(required = true) double time) {

    public AreaState {
        Check.text(name, "name");
        Check.positive(width, "width");
        Check.positive(height, "height");
        Check.nonNegative(time, "time");
    }

    public AreaState withTime(double newTime) {
        return new AreaState(name, width, height, newTime);
    }

    /** True if {@code p} is inside the area: x in [0, width), y in [0, height). */
    public boolean contains(Position p) {
        return p.x() >= 0.0 && p.x() < width && p.y() >= 0.0 && p.y() < height;
    }
}
