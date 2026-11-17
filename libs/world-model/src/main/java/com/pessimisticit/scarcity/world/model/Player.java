package com.pessimisticit.scarcity.world.model;

/** A character a user plays. */
@StoredResource
public final class Player extends Entity {

    /** For Jackson. */
    private Player() {
    }

    public Player(String prototype, String name) {
        super(prototype, name);
        validate();
    }

    @Override
    public EntityKind entityKind() {
        return EntityKind.PLAYER;
    }

    /** A player keeps its area running. */
    @Override
    public boolean occupies() {
        return true;
    }
}
