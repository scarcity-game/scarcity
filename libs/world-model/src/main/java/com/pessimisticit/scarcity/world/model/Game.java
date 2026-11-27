package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code game} (docs/resources.md, "Games"): its {@code name}, the {@code seed} a generated game
 * was made from (null on a hand-built one), and its {@code materialPatterns}, the wall pattern
 * every area of the game draws each material with ("Materials"): a {@link Materials} name to one
 * of that material's {@code patterns}. A seeded game's map is {@link MaterialPatterns#draw} of its
 * seed. The map is never null; empty (absent in JSON) when the game has none, and a client then
 * uses {@code content/sprites}' {@code materials}. Its children are its areas.
 *
 * <p>A stored map that no longer fits the catalogue (a material removed, a pattern no longer
 * allowed) is not an error: it is made again, from the seed, or each material's first pattern
 * without one ({@link #remade()}), and written so the next time the game is.
 */
@StoredResource
public final class Game extends Resource {

    @JsonProperty(required = true)
    private String name;
    private GameSeed seed;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> materialPatterns;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Faction> factions;
    @JsonIgnore
    private boolean remade;

    /** For Jackson. */
    private Game() {
    }

    /** A game with no seed and no material patterns of its own. */
    public Game(String name) {
        this(name, null, Map.of());
    }

    /** A generated game: its material patterns are drawn from {@code seed}. */
    public Game(String name, GameSeed seed) {
        this(name, Check.required(seed, "seed"), MaterialPatterns.draw(seed.value()).patterns());
    }

    /**
     * A hand-built game with these material patterns, which must fit the catalogue.
     *
     * @throws IllegalArgumentException naming {@code materialPatterns} if one doesn't
     */
    public Game(String name, Map<String, String> materialPatterns) {
        this(name, null, checked(materialPatterns));
    }

    private Game(String name, GameSeed seed, Map<String, String> materialPatterns) {
        this.name = name;
        this.seed = seed;
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

    /** What a generated game was made from; null on a hand-built one. */
    public GameSeed seed() {
        return seed;
    }

    /** Material name to pattern name, in the order written; never null. */
    public Map<String, String> materialPatterns() {
        return materialPatterns;
    }

    /**
     * The game's own faction relations (docs/resources.md, "Factions"), in the order written; never null,
     * empty (absent in JSON) when it has none and plays by {@link Factions#standard()}.
     */
    public Map<String, Faction> factions() {
        return factions;
    }

    /** The relations its creatures play by: its own, else the standard ones. */
    public Map<String, Faction> relations() {
        return factions.isEmpty() ? Factions.standard() : factions;
    }

    /** The same game with these faction relations of its own; empty plays by the standard ones. */
    public Game withFactions(Map<String, Faction> newFactions) {
        Game copy = copy();
        copy.factions = Factions.checked(newFactions);
        return copy;
    }

    /** True when the stored map no longer fitted the catalogue and was made again on read. */
    public boolean remade() {
        return remade;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(name, "name");
        materialPatterns = materialPatterns == null ? Map.of() : ordered(materialPatterns);
        factions = factions == null ? Map.of() : Factions.checked(factions);
        if (!MaterialPatterns.valid(materialPatterns)) {
            materialPatterns = seed != null ? MaterialPatterns.draw(seed.value()).patterns()
                    : Materials.fallbackPatterns();
            remade = true;
        }
    }

    private static Map<String, String> checked(Map<String, String> patterns) {
        Check.required(patterns, "materialPatterns").forEach((material, pattern) -> {
            String problem = MaterialPatterns.problem(material, pattern);
            if (problem != null) {
                throw new IllegalArgumentException("materialPatterns: " + problem);
            }
        });
        return patterns;
    }

    private static Map<String, String> ordered(Map<String, String> patterns) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(patterns));
    }
}
