package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An entity's {@code kind} (docs/resources.md, "Resource types"). */
public enum EntityKind {
    /** Moves by itself. */
    @JsonProperty("mobile") MOBILE,
    /** Doesn't move. */
    @JsonProperty("fixed") FIXED,
    /** A character. */
    @JsonProperty("player") PLAYER,
    /** A scripted character played by a service, in the same turn queue as players. */
    @JsonProperty("npc") NPC,
    /** A hostile or neutral creature, controlled by a service. */
    @JsonProperty("mob") MOB
}
