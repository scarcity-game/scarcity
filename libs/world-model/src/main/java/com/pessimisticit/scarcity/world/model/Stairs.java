package com.pessimisticit.scarcity.world.model;

/**
 * Stairs (docs/decisions-settled.md, 2026-11-18): two prototypes of one class, {@link #DOWN}
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

    private String toArea;

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

    /** The area that holds the paired stairs {@link #to}; null when they lead nowhere yet. */
    public String toArea() {
        return toArea;
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
        toArea = toArea == null || toArea.isEmpty() ? null : toArea;
        if ((to == null) != (toArea == null)) {
            throw new IllegalArgumentException("to, toArea: both are set or neither, got " + to + ", " + toArea);
        }
        if (to != null) {
            Ids.require("entity", to);
            Ids.require("area", toArea);
        }
    }

    /**
     * The same stairs leading to {@code destination}, the paired stairs' entity id, in {@code area};
     * both null means no level there yet.
     */
    public Stairs withTo(String destination, String area) {
        Stairs copy = copy();
        copy.to = destination;
        copy.toArea = area;
        copy.validate();
        return copy;
    }
}
