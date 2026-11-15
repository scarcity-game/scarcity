package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The state of an {@code area}: a {@code width} by {@code height} room (x in
 * [0, width), y in [0, height)), its game {@code time}, and whose {@code turns}
 * it is: the controlled idle entities, by {@code since} then entity id
 * (docs/actions.md, "Game time"). {@code turns} is never null; absent in JSON
 * reads as empty.
 */
public record AreaState(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) double width,
        @JsonProperty(required = true) double height,
        @JsonProperty(required = true) double time,
        List<Turn> turns) {

    public AreaState {
        Check.text(name, "name");
        Check.positive(width, "width");
        Check.positive(height, "height");
        Check.nonNegative(time, "time");
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** An area nobody is up in. */
    public AreaState(String name, double width, double height, double time) {
        this(name, width, height, time, List.of());
    }

    public AreaState withTime(double newTime) {
        return new AreaState(name, width, height, newTime, turns);
    }

    public AreaState withTurns(List<Turn> newTurns) {
        return new AreaState(name, width, height, time, newTurns);
    }

    /** True if {@code p} is inside the area: x in [0, width), y in [0, height). */
    public boolean contains(Position p) {
        return p.x() >= 0.0 && p.x() < width && p.y() >= 0.0 && p.y() < height;
    }
}
