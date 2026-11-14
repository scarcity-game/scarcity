package com.pessimisticit.scarcity.world.model;

/** A {@code area} resource: its id, its version and its typed state. */
public record Area(String id, long version, AreaState state) {

    public Area {
        Ids.require("area", id);
        if (version < 1) {
            throw new IllegalArgumentException("version: must be >= 1, got " + version);
        }
        Check.required(state, "state");
    }

    /** The same resource with a new state (the version is the store's to change). */
    public Area withState(AreaState newState) {
        return new Area(id, version, newState);
    }
}
