package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A thing that can't act and can be picked up (docs/resources.md, "Items and stashes"): for now
 * it lies in a {@link Stash}. Its {@link Measure} comes from its prototype: a {@code count} item
 * has a whole {@code count} of at least 1, and a {@code mass} item has none. {@code mass} is the
 * total, in grams, always above 0. Items with the same prototype are alike and {@link #combine}.
 */
@StoredResource
@JsonPropertyOrder({"resourceKind", "id", "version", "children", "prototype", "name", "measure", "count", "mass"})
public final class Item extends Resource {

    /** How an item is reckoned: by number (pebbles) or by weight (rubble). */
    public enum Measure {
        @JsonProperty("count") COUNT,
        @JsonProperty("mass") MASS
    }

    private String prototype;
    private String name;
    private Measure measure;
    private Double count;
    private Double mass;

    /** For Jackson. */
    private Item() {
    }

    @Override
    public ResourceKind resourceKind() {
        return ResourceKind.ITEM;
    }

    public String prototype() {
        return prototype;
    }

    public String name() {
        return name;
    }

    public Measure measure() {
        return measure;
    }

    /** How many; null for a {@code mass} item. */
    public Double count() {
        return count;
    }

    /** Grams, all of it. */
    public double mass() {
        return mass;
    }

    /** Whether {@code other} is alike: the same prototype. */
    public boolean likes(Item other) {
        return prototype.equals(other.prototype);
    }

    /** This item with {@code other}'s mass, and its count for a {@code count} item, added; this one's id. */
    public Item combine(Item other) {
        if (!likes(other)) {
            throw new IllegalArgumentException("item: a " + other.prototype + " doesn't combine with a " + prototype);
        }
        Item copy = copy();
        copy.mass = mass + other.mass;
        copy.count = count == null ? null : count + other.count;
        copy.validate();
        return copy;
    }

    /** One of a {@code count} item, or a {@code mass} item, of {@code grams}. */
    public Item of(double grams) {
        Item copy = copy();
        copy.mass = grams;
        copy.count = measure == Measure.COUNT ? 1.0 : null;
        copy.validate();
        return copy;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(prototype, "prototype");
        Check.text(name, "name");
        Check.required(measure, "measure");
        Check.required(mass, "mass");
        Check.positive(mass, "mass");
        if (measure == Measure.COUNT) {
            Check.required(count, "count");
            if (count < 1.0 || count != Math.rint(count)) {
                throw new IllegalArgumentException("count: a whole number of at least 1, got " + count);
            }
        } else if (count != null) {
            throw new IllegalArgumentException("count: a mass item has none");
        }
    }
}
