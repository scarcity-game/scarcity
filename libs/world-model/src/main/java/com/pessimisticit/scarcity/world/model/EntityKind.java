package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An entity's {@code entityKind} (docs/resources.md, "Resource types"). */
public enum EntityKind {
    /** Doesn't move: a pillar, stairs. */
    @JsonProperty("fixed") FIXED,
    /** A character. */
    @JsonProperty("player") PLAYER,
    /** A scripted character played by a service, in the same turn queue as players. */
    @JsonProperty("npc") NPC,
    /** A creature or a thing that moves, controlled by a service or by its behaviour. */
    @JsonProperty("mob") MOB
}
