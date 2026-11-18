package com.pessimisticit.scarcity.world.model;

/**
 * Stairs (docs/decisions.md): two prototypes of one class, {@link #DOWN}
 * ({@code stairs-down}) and {@link #UP} ({@code stairs-up}), so which way they
 * lead is the prototype. {@code to} is the id of the paired stairs entity on
 * the other level. Null (absent in JSON) means that level doesn't exist yet;
 * stairs down make it the first time someone takes them, and stairs up with no
 * {@code to} lead nowhere (there is no level above depth 0). The pair leads
 * back by its own {@code to}, so there is no {@code from}.
 */
@StoredResource
public final class Stairs extends Fixed {

    /** The prototype of stairs down, in {@link Prototypes}. */
    public static final String DOWN = "stairs-down";

    /** The prototype of stairs up, in {@link Prototypes}. */
    public static final String UP = "stairs-up";

    private String to;

    /** For Jackson. */
    private Stairs() {
    }

    /** Stairs down with the prototype's defaults, leading nowhere until someone takes them. */
    public static Stairs down(String name) {
        return (Stairs) Prototypes.get(DOWN).withName(name);
    }

    /** Stairs up with the prototype's defaults, leading nowhere until linked with {@link #withTo}. */
    public static Stairs up(String name) {
        return (Stairs) Prototypes.get(UP).withName(name);
    }

    public String to() {
        return to;
    }

    /** Whether these are stairs down (else up). */
    public boolean leadsDown() {
        return DOWN.equals(prototype());
    }

    @Override
    protected void validate() {
        super.validate();
        if (!DOWN.equals(prototype()) && !UP.equals(prototype())) {
            throw new IllegalArgumentException(
                    "prototype: stairs are '" + DOWN + "' or '" + UP + "', got " + prototype());
        }
        to = to == null || to.isEmpty() ? null : to;
        if (to != null) {
            Ids.require("entity", to);
        }
    }

    /** The same stairs leading to {@code destination}, an entity id; null means no level there yet. */
    public Stairs withTo(String destination) {
        Stairs copy = copy();
        copy.to = destination;
        copy.validate();
        return copy;
    }
}
