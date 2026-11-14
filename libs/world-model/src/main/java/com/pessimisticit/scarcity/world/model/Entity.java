package com.pessimisticit.scarcity.world.model;

/** A {@code entity} resource: its id, its version and its typed state. */
public record Entity(String id, long version, EntityState state) {

    public Entity {
        Ids.require("entity", id);
        if (version < 1) {
            throw new IllegalArgumentException("version: must be >= 1, got " + version);
        }
        Check.required(state, "state");
    }

    /** The same resource with a new state (the version is the store's to change). */
    public Entity withState(EntityState newState) {
        return new Entity(id, version, newState);
    }
}
