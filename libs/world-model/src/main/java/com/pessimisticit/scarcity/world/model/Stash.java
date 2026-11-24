package com.pessimisticit.scarcity.world.model;

/**
 * Items lying together on the floor (docs/resources.md, "Items and stashes"): a fixed entity at a
 * position, of radius {@link #RADIUS}, that others can stand in, with its items as children. An item
 * on the floor is always in one, and a stash goes away with its last item. It is no physical object
 * itself: its items have the mass.
 */
@StoredResource
public final class Stash extends Fixed {

    /** The prototype name, in {@link Prototypes}. */
    public static final String PROTOTYPE = "stash";

    /** The stash radius, in metres: an item that comes to rest this near a stash joins it. */
    public static final double RADIUS = 0.5;

    /** For Jackson. */
    private Stash() {
    }

    /** A new, empty stash at {@code position}. */
    public static Stash at(Position position) {
        return (Stash) Prototypes.get(PROTOTYPE).withPosition(position);
    }

    @Override
    public boolean physical() {
        return false;
    }

    @Override
    protected void validate() {
        super.validate();
        if (!PROTOTYPE.equals(prototype())) {
            throw new IllegalArgumentException("prototype: a stash is a '" + PROTOTYPE + "', not '" + prototype() + "'");
        }
        if (radius() == null || radius() != RADIUS) {
            throw new IllegalArgumentException("radius: a stash's is " + RADIUS + ", got " + radius());
        }
    }
}
