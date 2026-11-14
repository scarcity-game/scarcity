package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The state of a {@code game} resource. */
public record GameState(
        @JsonProperty(required = true) String name) {

    public GameState {
        Check.text(name, "name");
    }
}
