package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Holds an entity's place in an area it has not arrived in yet
 * (docs/actions.md, "Crossing areas"): the {@code entity} that left by the
 * stairs, which takes no part in play (it has no controller, no radius and
 * nothing acts on it). It is a fixed entity at the arrival stairs whose
 * {@code arrive} action ends when the area has caught up to the game, and
 * the entity appears then, with the same id.
 */
@StoredResource
public final class Placeholder extends Fixed {

    /** The prototype name; it has no prototype file, as it needs an {@code entity}. */
    public static final String PROTOTYPE = "placeholder";

    @JsonProperty(required = true)
    private Entity entity;

    /** For Jackson. */
    private Placeholder() {
    }

    /** @param entity the traveller as it left: idle, with its id and controller */
    public Placeholder(String name, Entity entity) {
        super(PROTOTYPE, name);
        Check.required(entity, "entity");
        this.entity = entity;
        validate();
    }

    /** The entity that is on its way. */
    public Entity entity() {
        return entity;
    }

    @Override
    protected void validate() {
        super.validate();
        if (!PROTOTYPE.equals(prototype())) {
            throw new IllegalArgumentException("prototype: a placeholder is '" + PROTOTYPE + "', got " + prototype());
        }
        // The superclass constructor validates before the entity is set; the constructor checks it then.
        if (entity != null) {
            Ids.require("entity", entity.id());
        }
    }

    /** It only holds a place: it has no mass and no radius. */
    @Override
    public boolean physical() {
        return false;
    }

    /** An arrival runs the area up to game time (docs/actions.md, "Crossing areas"). */
    @Override
    public boolean occupies() {
        return true;
    }
}
