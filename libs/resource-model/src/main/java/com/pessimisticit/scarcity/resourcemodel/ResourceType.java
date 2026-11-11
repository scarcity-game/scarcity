package com.pessimisticit.scarcity.resourcemodel;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Resource kinds. Serialised lower case; also the id prefix. */
public enum ResourceType {
    GAME, AREA, ENTITY, ITEM;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The id prefix including the colon, e.g. {@code "area:"}. */
    public String prefix() {
        return wire() + ":";
    }

    @JsonCreator
    public static ResourceType fromWire(String s) {
        for (ResourceType t : values()) {
            if (t.wire().equals(s)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown resource type: " + s);
    }
}
