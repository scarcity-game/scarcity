package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An item prototype by name ({@link ItemPrototypes}), as rules' params name one. */
public record ItemTemplate(@JsonProperty(required = true) String prototype) {

    public ItemTemplate {
        Check.text(prototype, "prototype");
        ItemPrototypes.get(prototype);
    }

    /** One of it, or that much of it ({@link Item#of}), of {@code grams}. */
    public Item of(double grams) {
        return ItemPrototypes.get(prototype).of(grams);
    }
}
