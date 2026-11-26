package com.pessimisticit.scarcity.world.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A game's material to pattern map (docs/resources.md, "Materials"), drawn from its
 * {@link GameSeed}'s value alone, so the same seed and catalogue give the same map. For each
 * material in {@link Materials#all()} order, one of its {@code patterns}: a uniform over [0, n),
 * floored ({@code game.pattern.<material>}); a material with one pattern takes it without a draw.
 */
public final class MaterialPatterns {

    /** The prefix of the draws' names: {@code game.pattern.<material>}. */
    public static final String PREFIX = "game.pattern.";

    private MaterialPatterns() {
    }

    /** The map and each draw that made it, by name, in order. */
    public record Drawn(Map<String, String> patterns, Map<String, Draw> draws) {

        public Drawn {
            patterns = Collections.unmodifiableMap(new LinkedHashMap<>(patterns));
            draws = Collections.unmodifiableMap(new LinkedHashMap<>(draws));
        }
    }

    /** The map {@code seed} makes. */
    public static Drawn draw(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        Map<String, String> patterns = new LinkedHashMap<>();
        Map<String, Draw> draws = new LinkedHashMap<>();
        for (Material m : Materials.all().stream().filter(Material::drawable).toList()) {
            List<String> candidates = m.patterns();
            int index = 0;
            if (candidates.size() > 1) {
                Draw.Uniform u = Draws.uniform(rng, 0.0, candidates.size());
                draws.put(PREFIX + m.name(), u);
                index = Math.min(candidates.size() - 1, (int) Math.floor(u.value()));
            }
            patterns.put(m.name(), candidates.get(index));
        }
        return new Drawn(patterns, draws);
    }

    /** Whether every entry names a catalogue material and one of its patterns. */
    public static boolean valid(Map<String, String> patterns) {
        return patterns.entrySet().stream().allMatch(e -> problem(e.getKey(), e.getValue()) == null);
    }

    /** What is wrong with drawing {@code material} with {@code pattern}; null if nothing. */
    static String problem(String material, String pattern) {
        Material known = Materials.find(material).orElse(null);
        if (known == null) {
            return "unknown material '" + material + "'";
        }
        if (pattern == null || !known.patterns().contains(pattern)) {
            return material + " is never drawn with '" + pattern + "' (its patterns are " + known.patterns() + ")";
        }
        return null;
    }
}
