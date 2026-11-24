package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What a wall or a pillar is made of (docs/resources.md, "Materials"): its {@code name} (an
 * entity's {@code material}), its {@code density} in grams per cubic metre, the {@code weight}
 * level generation gives it when it draws a level's walls, and the wall {@code patterns}
 * ({@code content/sprites}, {@code patterns}) a game may draw it with, the first the fallback. The
 * catalogue is {@link Materials}; no code compares a material's name.
 */
public record Material(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) double density,
        @JsonProperty(required = true) double weight,
        @JsonProperty(required = true) List<String> patterns) {

    /** How tall a wall or a pillar stands, floor to ceiling, in metres. */
    public static final double HEIGHT = 3.0;

    public Material {
        if (!Check.text(name, "name").matches("[a-z][a-z-]*")) {
            throw new IllegalArgumentException("name: not a material name: " + name);
        }
        Check.positive(density, "density");
        Check.positive(weight, "weight");
        patterns = List.copyOf(Check.required(patterns, "patterns"));
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("patterns: a material needs at least one");
        }
        patterns.forEach(p -> Check.text(p, "patterns"));
    }

    /** The mass, in grams, of a column of this material {@link #HEIGHT} tall on {@code footprint} square metres. */
    public double mass(double footprint) {
        return density * Check.positive(footprint, "footprint") * HEIGHT;
    }

    /**
     * {@code entity} made of this material: its {@code material} is this one's name and its
     * {@code mass} that of a column {@link #HEIGHT} tall on its footprint (its polygon's area, or
     * πr² for its radius).
     */
    public Entity make(Entity entity) {
        double footprint = entity.polygon() != null
                ? ConvexPolygon.area(entity.polygon())
                : Math.PI * Check.required(entity.radius(), "radius") * entity.radius();
        return entity.withMaterial(name).withMass(mass(footprint));
    }

    /** A wall of this material with this outline: the {@code wall} prototype, named "{@code <name>} wall". */
    public Entity wall(List<Position> polygon) {
        return make(Prototypes.get("wall").withPolygon(polygon).withName(name + " wall"));
    }
}
