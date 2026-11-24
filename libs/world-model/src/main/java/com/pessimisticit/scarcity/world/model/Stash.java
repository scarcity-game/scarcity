package com.pessimisticit.scarcity.world.model;

/**
 * Items lying together on the floor (docs/resources.md, "Items and stashes"): a fixed entity at a
 * position, whose radius grows with the mass of its items ({@link #radiusFor}), that others can stand in, with its items as children. An item
 * on the floor is always in one, and a stash goes away with its last item. It is no physical object
 * itself: its items have the mass.
 */
@StoredResource
public final class Stash extends Fixed {

    /** The prototype name, in {@link Prototypes}. */
    public static final String PROTOTYPE = "stash";

    /** The smallest stash radius, in metres: an empty or light stash. */
    public static final double MIN_RADIUS = 0.1;

    /** The largest stash radius, in metres. */
    public static final double MAX_RADIUS = 1.0;

    /** An item that comes to rest within this many radii of a stash's position joins it. */
    public static final double JOIN_RADII = 2.0;

    /** For Jackson. */
    private Stash() {
    }

    /**
     * The radius, in metres, of a stash holding {@code grams} in all: {@code 0.1 * log10(grams)},
     * clamped to [{@link #MIN_RADIUS}, {@link #MAX_RADIUS}]. So 10 g is 0.1 m, 1 kg 0.3 m, 1 Mg 0.6 m.
     */
    public static double radiusFor(double grams) {
        return Math.clamp(0.1 * Math.log10(grams), MIN_RADIUS, MAX_RADIUS);
    }

    /** How far from the stash's position an item may rest and still join it. */
    public double joinDistance() {
        return JOIN_RADII * radius();
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
        if (radius() == null || radius() < MIN_RADIUS || radius() > MAX_RADIUS) {
            throw new IllegalArgumentException(
                    "radius: a stash's is in [" + MIN_RADIUS + ", " + MAX_RADIUS + "], got " + radius());
        }
    }
}
