package com.pessimisticit.scarcity.world.model;

/**
 * Stairs down (docs/decisions.md): {@code to} is the id of the paired stairs
 * entity on the level below. Null (absent in JSON) means that level doesn't
 * exist yet; it is made the first time someone uses the stairs. The pair
 * leads back by its own {@code to}, so there is no {@code from}.
 */
@StoredResource
public final class Stairs extends Fixed {

    /** The prototype name, in {@link Prototypes}. */
    public static final String PROTOTYPE = "stairs";

    private String to;

    /** For Jackson. */
    private Stairs() {
    }

    public Stairs(String name) {
        super(PROTOTYPE, name);
        validate();
    }

    public String to() {
        return to;
    }

    @Override
    protected void validate() {
        super.validate();
        if (!PROTOTYPE.equals(prototype())) {
            throw new IllegalArgumentException("prototype: stairs are '" + PROTOTYPE + "', got " + prototype());
        }
        to = to == null || to.isEmpty() ? null : to;
        if (to != null) {
            Ids.require("entity", to);
        }
    }

    /** The same stairs leading to {@code destination}, an entity id; null means no level below yet. */
    public Stairs withTo(String destination) {
        Stairs copy = copy();
        copy.to = destination;
        copy.validate();
        return copy;
    }
}
