package com.pessimisticit.scarcity.world.model;

/** An entity that doesn't move: a pillar, rubble, stairs. */
@StoredResource
public class Fixed extends Entity {

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
}
