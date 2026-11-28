package com.pessimisticit.scarcity.world.model;

import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

/**
 * A creature's attributes (docs/resources.md, "Attributes"): {@code level}, {@code strength},
 * {@code dexterity} and {@code intelligence}, each a float > 0. An entity has all four or none;
 * they are flat keys of the entity, and this record is how code reads and sets them together.
 */
public record Attributes(double level, double strength, double dexterity, double intelligence) {

    public Attributes {
        Check.positive(level, "level");
        Check.positive(strength, "strength");
        Check.positive(dexterity, "dexterity");
        Check.positive(intelligence, "intelligence");
    }

    /** Checks an entity's flat keys: all four or none, and each above 0 when there are. */
    static void checkAllOrNone(Double level, Double strength, Double dexterity, Double intelligence) {
        boolean any = level != null || strength != null || dexterity != null || intelligence != null;
        boolean all = level != null && strength != null && dexterity != null && intelligence != null;
        if (any && !all) {
            throw new IllegalArgumentException("level: an entity has level, strength, dexterity and intelligence, or none");
        }
        if (all) {
            new Attributes(level, strength, dexterity, intelligence);
        }
    }

    /**
     * These attributes varied as a generated resource's are ({@link Draws#vary}, sd
     * {@link Draws#VARIATION} of each), drawn in the order level, strength, dexterity, intelligence;
     * each draw is passed to {@code record} under the attribute's name.
     */
    public Attributes vary(RandomGenerator rng, BiConsumer<String, Draw.Gaussian> record) {
        return new Attributes(
                varied(rng, record, "level", level),
                varied(rng, record, "strength", strength),
                varied(rng, record, "dexterity", dexterity),
                varied(rng, record, "intelligence", intelligence));
    }

    private static double varied(RandomGenerator rng, BiConsumer<String, Draw.Gaussian> record, String name,
            double value) {
        Draw.Gaussian draw = Draws.vary(rng, value, Draws.VARIATION);
        record.accept(name, draw);
        return draw.value();
    }
}
