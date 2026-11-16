package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An entity that doesn't move: a pillar, rubble. {@code overlap}: other
 * entities may stand on it. {@code damageable}: damage affects its
 * {@code hp}. Both are always written, and absent in JSON reads as false.
 */
@StoredResource
public class Fixed extends Entity {

    @JsonProperty
    private Boolean overlap;
    @JsonProperty
    private Boolean damageable;

    /** For Jackson. */
    protected Fixed() {
    }

    public Fixed(String prototype, String name) {
        super(prototype, name);
        validate();
    }

    @Override
    public EntityKind entityKind() {
        return EntityKind.FIXED;
    }

    public boolean overlap() {
        return overlap;
    }

    public boolean damageable() {
        return damageable;
    }

    @Override
    protected void validate() {
        super.validate();
        overlap = overlap != null && overlap;
        damageable = damageable != null && damageable;
    }

    public final Fixed withOverlap(boolean newOverlap) {
        Fixed copy = copy();
        copy.overlap = newOverlap;
        return copy;
    }

    public final Fixed withDamageable(boolean newDamageable) {
        Fixed copy = copy();
        copy.damageable = newDamageable;
        return copy;
    }
}
