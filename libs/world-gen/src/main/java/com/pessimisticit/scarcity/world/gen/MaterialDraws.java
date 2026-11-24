package com.pessimisticit.scarcity.world.gen;

import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import java.util.List;

/**
 * The material choice of level generation (docs/resources.md, "Materials"): what a level's walls
 * are made of, a named draw over the {@link Materials} catalogue in its order. A game's material
 * patterns are drawn from its seed instead ({@code MaterialPatterns}, libs/world-model).
 */
public final class MaterialDraws {

    /** The draw that picks a level's wall material. */
    public static final String WALL = "level.material";

    private MaterialDraws() {
    }

    /** A level's wall material: a categorical over the catalogue by each material's {@code weight}. */
    public static Material wall(Recorder recorder) {
        List<Material> all = Materials.all();
        return all.get(recorder.categorical(WALL, all.stream().map(Material::weight).toList()));
    }
}
