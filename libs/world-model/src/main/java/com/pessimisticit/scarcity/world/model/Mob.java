package com.pessimisticit.scarcity.world.model;

/** A creature or a thing that moves: a mouse, a pebble in flight. */
@StoredResource
public final class Mob extends Entity {

    /** For Jackson. */
    private Mob() {
    }

    public Mob(String prototype, String name) {
        super(prototype, name);
        validate();
    }

    @Override
    public EntityKind entityKind() {
        return EntityKind.MOB;
    }
}
