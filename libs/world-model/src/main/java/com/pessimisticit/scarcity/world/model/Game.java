package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code game}: its {@code name} and its {@code materialPatterns}, the wall pattern every area of
 * the game draws each material with (docs/resources.md, "Materials"): a {@link Materials} name to
 * one of that material's {@code patterns}. Never null; empty (absent in JSON) when the game has
 * none, and a client then uses {@code content/sprites}' {@code materials}. Its children are its
 * areas.
 */
@StoredResource
public final class Game extends Resource {

    @JsonProperty(required = true)
    private String name;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> materialPatterns;

    /** For Jackson. */
    private Game() {
    }

    /** A game with no material patterns of its own. */
    public Game(String name) {
        this(name, Map.of());
    }

    public Game(String name, Map<String, String> materialPatterns) {
        this.name = name;
        this.materialPatterns = materialPatterns;
        validate();
    }

    @Override
    public ResourceKind resourceKind() {
        return ResourceKind.GAME;
    }

    public String name() {
        return name;
    }

    /** Material name to pattern name, in the order written; never null. */
    public Map<String, String> materialPatterns() {
        return materialPatterns;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(name, "name");
        materialPatterns = materialPatterns == null ? Map.of() : orderedCopy(materialPatterns);
        materialPatterns.forEach(Game::checkPattern);
    }

    private static Map<String, String> orderedCopy(Map<String, String> patterns) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(patterns));
    }

    private static void checkPattern(String material, String pattern) {
        Material known;
        try {
            known = Materials.get(material);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("materialPatterns: unknown material '" + material + "'", e);
        }
        if (!known.patterns().contains(Check.text(pattern, "materialPatterns"))) {
            throw new IllegalArgumentException("materialPatterns: " + material + " is never drawn with '" + pattern
                    + "' (its patterns are " + known.patterns() + ")");
        }
    }
}
