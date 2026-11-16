package com.pessimisticit.scarcity.world.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code area}: a {@code width} by {@code height} room (x in [0, width),
 * y in [0, height)), its {@code depth} (0 is the first level; a larger depth is
 * further down; absent in JSON reads as 0 and is always written), its game
 * {@code time}, and whose turn it is (docs/actions.md, "Turns"): the
 * controlled idle entities by {@code since}, then entity id. The first is
 * {@code activeEntity}, the only one that may act; the rest are
 * {@code pending}. {@code activeEntity} is null (absent in JSON) when nobody
 * is; {@code pending} is never null, and absent in JSON reads as empty. Its
 * children are its entities.
 */
@StoredResource
public final class Area extends Resource {

    private String name;
    private Double width;
    private Double height;
    private Double time;
    private Integer depth;
    private Turn activeEntity;
    private List<Turn> pending;

    /** For Jackson. */
    private Area() {
    }

    /** An area with no active entity, on the first level. */
    public Area(String name, double width, double height, double time) {
        this(name, width, height, time, 0, null, List.of());
    }

    public Area(String name, double width, double height, double time, Integer depth,
            Turn activeEntity, List<Turn> pending) {
        this.name = name;
        this.width = width;
        this.height = height;
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

    public double width() {
        return width;
    }

    public double height() {
        return height;
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
        Check.positive(Check.required(width, "width"), "width");
        Check.positive(Check.required(height, "height"), "height");
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

    /** True if {@code p} is inside the area: x in [0, width), y in [0, height). */
    public boolean contains(Position p) {
        return p.x() >= 0.0 && p.x() < width && p.y() >= 0.0 && p.y() < height;
    }
}
