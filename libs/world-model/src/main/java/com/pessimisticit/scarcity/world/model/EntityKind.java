package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An entity's {@code kind} (docs/resources.md, "Resource types"). */
public enum EntityKind {
    /** Moves by itself. */
    @JsonProperty("mobile") MOBILE,
    /** Doesn't move. */
    @JsonProperty("fixed") FIXED,
    /** A character. */
    @JsonProperty("player") PLAYER
}
