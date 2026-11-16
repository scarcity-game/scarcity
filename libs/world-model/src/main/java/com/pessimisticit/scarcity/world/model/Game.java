package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A {@code game}: its {@code name}; its children are its areas. */
@StoredResource
public final class Game extends Resource {

    @JsonProperty(required = true)
    private String name;

    /** For Jackson. */
    private Game() {
    }

    public Game(String name) {
        this.name = name;
        validate();
    }

    @Override
    public ResourceKind resourceKind() {
        return ResourceKind.GAME;
    }

    public String name() {
        return name;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(name, "name");
    }
}
