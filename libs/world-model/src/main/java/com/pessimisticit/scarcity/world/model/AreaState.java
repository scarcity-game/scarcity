package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * The state of an {@code area}: a {@code width} by {@code height} room (x in
 * [0, width), y in [0, height)), its game {@code time}, and whose turn it is
 * (docs/actions.md, "Turns"): the controlled idle entities by {@code since},
 * then entity id. The first is {@code activeEntity}, the only one that may act; the rest
 * are {@code pending}. {@code activeEntity} is null (absent in JSON) when nobody is;
 * {@code pending} is never null, and absent in JSON reads as empty.
 */
public record AreaState(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) double width,
        @JsonProperty(required = true) double height,
        @JsonProperty(required = true) double time,
        Turn activeEntity,
        List<Turn> pending) {

    public AreaState {
        Check.text(name, "name");
        Check.positive(width, "width");
        Check.positive(height, "height");
        Check.nonNegative(time, "time");
        pending = pending == null ? List.of() : List.copyOf(pending);
        if (activeEntity == null && !pending.isEmpty()) {
            throw new IllegalArgumentException("pending: nobody is active, so nobody is pending");
        }
    }

    /** An area with no active entity. */
    public AreaState(String name, double width, double height, double time) {
        this(name, width, height, time, null, List.of());
    }

    public AreaState withTime(double newTime) {
        return new AreaState(name, width, height, newTime, activeEntity, pending);
    }

    /** {@code queue} in order (by since, then id): its first is the active entity, the rest pending. */
    public AreaState withQueue(List<Turn> queue) {
        return queue.isEmpty()
                ? new AreaState(name, width, height, time, null, List.of())
                : new AreaState(name, width, height, time, queue.getFirst(), queue.subList(1, queue.size()));
    }

    /** {@code activeEntity}, then {@code pending}. */
    public List<Turn> queue() {
        if (activeEntity == null) {
            return List.of();
        }
        List<Turn> all = new ArrayList<>();
        all.add(activeEntity);
        all.addAll(pending);
        return List.copyOf(all);
    }

    /** True if {@code p} is inside the area: x in [0, width), y in [0, height). */
    public boolean contains(Position p) {
        return p.x() >= 0.0 && p.x() < width && p.y() >= 0.0 && p.y() < height;
    }
}
