package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A resource's {@code resourceKind} (docs/resources.md, "Resource types"). */
public enum ResourceKind {
    @JsonProperty("game") GAME("game"),
    @JsonProperty("area") AREA("area"),
    @JsonProperty("entity") ENTITY("entity");

    private final String prefix;

    ResourceKind(String prefix) {
        this.prefix = prefix;
    }

    /** The id prefix: {@code area} for {@code area:<uuid>}. */
    public String prefix() {
        return prefix;
    }
}
