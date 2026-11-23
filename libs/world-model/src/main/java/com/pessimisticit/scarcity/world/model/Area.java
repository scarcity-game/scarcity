package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@code area}: a {@code name}, its {@code depth} (0 is the first level; a larger depth is
 * further down; absent in JSON reads as 0 and is always written), its game
 * {@code time}, and whose turn it is (docs/actions.md, "Turns"): the
 * controlled idle entities by {@code since}, then entity id. The first is
 * {@code activeEntity}, the only one that may act; the rest are
 * {@code pending}. {@code activeEntity} is null (absent in JSON) when nobody
 * is; {@code pending} is never null, and absent in JSON reads as empty. Its
 * children are its entities. It has no size: its walls are its edges
 * (docs/resources.md, "Walls"). The {@code width} and {@code height} that
 * areas stored before #298 have are ignored on read, and gone once it is
 * written again.
 */
@StoredResource
public final class Area extends Resource {

    private String name;
    private Double time;
    private Integer depth;
    private Turn activeEntity;
    private List<Turn> pending;

    /** For Jackson. */
    private Area() {
    }

    /** Read and dropped: an area stored before #298 has a width. */
    @JsonSetter("width")
    @SuppressWarnings("unused")
    private void storedWidth(double ignored) {
        // The walls are the area's edges now.
    }

    /** Read and dropped: an area stored before #298 has a height. */
    @JsonSetter("height")
    @SuppressWarnings("unused")
    private void storedHeight(double ignored) {
        // The walls are the area's edges now.
    }

    /** An area with no active entity, on the first level. */
    public Area(String name, double time) {
        this(name, time, 0, null, List.of());
    }

    public Area(String name, double time, Integer depth,
            Turn activeEntity, List<Turn> pending) {
        this.name = name;
        this.time = time;
        this.depth = depth;
        this.activeEntity = activeEntity;
        this.pending = pending;
        validate();
    }

    @Override
    public ResourceKind resourceKind() {
        return ResourceKind.AREA;
    }

    public String name() {
        return name;
    }

    public double time() {
        return time;
    }

    public int depth() {
        return depth;
    }

    public Turn activeEntity() {
        return activeEntity;
    }

    public List<Turn> pending() {
        return pending;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(name, "name");
        Check.nonNegative(Check.required(time, "time"), "time");
        depth = depth == null ? 0 : depth;
        if (depth < 0) {
            throw new IllegalArgumentException("depth: must not be negative, got " + depth);
        }
        pending = pending == null ? List.of() : List.copyOf(pending);
        if (activeEntity == null && !pending.isEmpty()) {
            throw new IllegalArgumentException("pending: nobody is active, so nobody is pending");
        }
    }

    public Area withTime(double newTime) {
        Area copy = copy();
        copy.time = newTime;
        copy.validate();
        return copy;
    }

    /** {@code queue} in order (by since, then id): its first is the active entity, the rest pending. */
    public Area withQueue(List<Turn> queue) {
        Area copy = copy();
        copy.activeEntity = queue.isEmpty() ? null : queue.getFirst();
        copy.pending = queue.isEmpty() ? List.of() : queue.subList(1, queue.size());
        copy.validate();
        return copy;
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
}
