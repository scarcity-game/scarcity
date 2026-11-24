package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The material choices of generation (docs/resources.md, "Materials"), each a named draw over the
 * {@link Materials} catalogue in its order: what a level's walls are made of, and how a game draws
 * each material.
 */
public final class MaterialDraws {

    /** The draw that picks a level's wall material. */
    public static final String WALL = "level.material";

    /** The prefix of a game's pattern draws: {@code game.pattern.<material>}. */
    public static final String PATTERN = "game.pattern.";

    private MaterialDraws() {
    }

    /** A level's wall material: a categorical over the catalogue by each material's {@code weight}. */
    public static Material wall(Recorder recorder) {
        List<Material> all = Materials.all();
        return all.get(recorder.categorical(WALL, all.stream().map(Material::weight).toList()));
    }

    /**
     * A game's material patterns: for each material in catalogue order, one of its {@code patterns}
     * ({@code game.pattern.<material>}, a uniform index), so every area of the game made of it
     * looks the same. A material with one pattern takes it without a draw.
     */
    public static Map<String, String> patterns(Recorder recorder) {
        Map<String, String> patterns = new LinkedHashMap<>();
        for (Material m : Materials.all()) {
            patterns.put(m.name(), pattern(recorder, m));
        }
        return patterns;
    }

    private static String pattern(Recorder recorder, Material material) {
        List<String> candidates = material.patterns();
        return candidates.size() == 1
                ? candidates.getFirst()
                : candidates.get(recorder.index(PATTERN + material.name(), candidates.size()));
    }
}
