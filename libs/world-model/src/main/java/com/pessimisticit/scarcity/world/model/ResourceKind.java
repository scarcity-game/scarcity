package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A resource's {@code resourceKind} (docs/resources.md, "Resource types"). */
public enum ResourceKind {
    @JsonProperty("game") GAME("game"),
    @JsonProperty("area") AREA("area"),
    @JsonProperty("entity") ENTITY("entity"),
    @JsonProperty("item") ITEM("item");

    private final String prefix;

    ResourceKind(String prefix) {
        this.prefix = prefix;
    }

    /** The kind whose prefix (and wire name) is {@code name}; throws IllegalArgumentException if none. */
    public static ResourceKind of(String name) {
        for (ResourceKind kind : values()) {
            if (kind.prefix.equals(name)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown resource type: " + name);
    }

    /** The id prefix: {@code area} for {@code area:<uuid>}. */
    public String prefix() {
        return prefix;
    }
}
