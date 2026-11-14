package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * The state of an {@code area}: a {@code width} by {@code height} room (x in
 * [0, width), y in [0, height)) and its game {@code time} (docs/actions.md,
 * "Game time"). Unknown keys are kept in {@code extra}.
 */
public record AreaState(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) double width,
        @JsonProperty(required = true) double height,
        @JsonProperty(required = true) double time,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> extra) {

    private static final Set<String> KNOWN = Set.of("name", "width", "height", "time");

    public AreaState {
        Check.text(name, "name");
        Check.positive(width, "width");
        Check.positive(height, "height");
        Check.nonNegative(time, "time");
        extra = Extra.copy(extra, KNOWN);
    }

    public AreaState(String name, double width, double height, double time) {
        this(name, width, height, time, Map.of());
    }

    public AreaState withTime(double newTime) {
        return new AreaState(name, width, height, newTime, extra);
    }

    /** True if {@code p} is inside the area: x in [0, width), y in [0, height). */
    public boolean contains(Position p) {
        return p.x() >= 0.0 && p.x() < width && p.y() >= 0.0 && p.y() < height;
    }
}
