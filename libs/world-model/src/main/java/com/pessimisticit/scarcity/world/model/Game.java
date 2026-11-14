package com.pessimisticit.scarcity.world.model;

/** A {@code game} resource: its id, its version and its typed state. */
public record Game(String id, long version, GameState state) {

    public Game {
        Ids.require("game", id);
        if (version < 1) {
            throw new IllegalArgumentException("version: must be >= 1, got " + version);
        }
        Check.required(state, "state");
    }

    /** The same resource with a new state (the version is the store's to change). */
    public Game withState(GameState newState) {
        return new Game(id, version, newState);
    }
}
