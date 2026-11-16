package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Which way a stairs entity leads (docs/resources.md, "Resource types"). */
public enum Direction {
    /** To the level above: a smaller depth. */
    @JsonProperty("up") UP,
    /** To the level below: a larger depth. */
    @JsonProperty("down") DOWN
}
