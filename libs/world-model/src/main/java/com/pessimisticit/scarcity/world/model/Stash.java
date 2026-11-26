package com.pessimisticit.scarcity.world.model;

/**
 * Items lying together on the floor (docs/resources.md, "Items and stashes"): a fixed entity at a
 * position, whose radius is that of a pile of its items' mass ({@link #radiusFor}), that others can stand in, with its items as children. An item
 * on the floor is always in one, and a stash goes away with its last item. Its {@code mass} is its
 * items' total, kept current by the engine with its radius; it is no physical object itself, so it
 * adds no mass of its own.
 */
@StoredResource
public final class Stash extends Fixed {

    /** The prototype name, in {@link Prototypes}. */
    public static final String PROTOTYPE = "stash";

    /** The smallest stash radius, in metres: an empty or light stash. */
    public static final double MIN_RADIUS = 0.1;

    /** The largest stash radius, in metres. */
    public static final double MAX_RADIUS = 1.0;

    /**
     * The bulk density of a pile of items, in grams per cubic metre: loose rock (about 2.7 t/m³) packs
     * to about 60%, so a pile weighs about 1.6 t/m³.
     */
    public static final double PILE_DENSITY = 1_600_000.0;

    /** An item that comes to rest within this many radii of a stash's position joins it. */
    public static final double JOIN_RADII = 2.0;

    /** For Jackson. */
    private Stash() {
    }

    /**
     * The radius, in metres, of a stash holding {@code grams} in all: the radius of a hemispherical pile
     * of that mass at {@link #PILE_DENSITY}, {@code cbrt(3 * grams / (2 * PI * PILE_DENSITY))}, clamped to
     * [{@link #MIN_RADIUS}, {@link #MAX_RADIUS}]. So up to about 3.35 kg is 0.1 m, 10 kg 0.144 m,
     * 100 kg 0.310 m, 1 t 0.668 m, and from about 3.35 t it is 1.0 m.
     */
    public static double radiusFor(double grams) {
        double volume = Math.max(grams, 0.0) / PILE_DENSITY;
        return Math.clamp(Math.cbrt(3.0 * volume / (2.0 * Math.PI)), MIN_RADIUS, MAX_RADIUS);
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
