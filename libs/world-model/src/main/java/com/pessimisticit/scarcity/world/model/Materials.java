package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The materials catalogue, {@code /materials.json} (docs/resources.md, "Materials"): every
 * {@link Material}, in the file's order, which is the order generation draws over them.
 */
public final class Materials {

    private static final Map<String, Material> BY_NAME = load();

    /**
     * The marker material of the walls on an area's outline (weight 0: never drawn as a level's
     * material, and not in a game's {@code materialPatterns}); it is always drawn with its own pattern.
     */
    public static final String BEDROCK = "bedrock";

    private Materials() {
    }

    /** The file's shape: {@code {"materials": [...]}}. */
    record Catalogue(@JsonProperty(required = true) List<Material> materials) {
    }

    /** Every material, in the catalogue's order. */
    public static List<Material> all() {
        return List.copyOf(BY_NAME.values());
    }

    /**
     * The material of this name.
     *
     * @throws IllegalArgumentException if there is none
     */
    public static Material get(String name) {
        Material material = BY_NAME.get(Check.text(name, "material"));
        if (material == null) {
            throw new IllegalArgumentException("material: unknown material '" + name + "'");
        }
        return material;
    }

    /** The material of this name, if the catalogue has it. */
    public static Optional<Material> find(String name) {
        return Optional.ofNullable(name == null ? null : BY_NAME.get(name));
    }

    /**
     * Each drawable material's first pattern: how a game with no drawn map, such as the test game, draws
     * its walls. A material with weight 0 is left out; a client draws it with the catalogue's default.
     */
    public static Map<String, String> fallbackPatterns() {
        Map<String, String> patterns = new LinkedHashMap<>();
        BY_NAME.values().stream().filter(Material::drawable).forEach(m -> patterns.put(m.name(), m.patterns().getFirst()));
        return patterns;
    }

    private static Map<String, Material> load() {
        String path = "/materials.json";
        try (InputStream in = Materials.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            Catalogue catalogue = WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    Catalogue.class);
            Map<String, Material> byName = new LinkedHashMap<>();
            for (Material m : catalogue.materials()) {
                if (byName.put(m.name(), m) != null) {
                    throw new IllegalStateException(path + ": material " + m.name() + " is listed twice");
                }
            }
            return byName;
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
