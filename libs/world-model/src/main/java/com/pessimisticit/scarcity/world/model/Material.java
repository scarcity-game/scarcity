package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What a wall or a pillar is made of (docs/resources.md, "Materials"): its {@code name} (an
 * entity's {@code material}), its {@code density} in grams per cubic metre, the {@code weight}
 * level generation gives it when it draws a level's walls, and the wall {@code patterns}
 * ({@code content/sprites}, {@code patterns}) a game may draw it with, the first the fallback. The
 * catalogue is {@link Materials}; no code compares a material's name. A {@code weight} of 0 marks a
 * material no level draws and no game draws patterns for (bedrock, {@link Materials#BEDROCK}); it
 * keeps its one pattern.
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
        Check.nonNegative(weight, "weight");
        patterns = List.copyOf(Check.required(patterns, "patterns"));
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("patterns: a material needs at least one");
        }
        patterns.forEach(p -> Check.text(p, "patterns"));
    }

    /** Whether level generation may draw this material as a wall: its {@code weight} is above 0. */
    public boolean drawable() {
        return weight > 0;
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

    /**
     * The wall of {@code wallCell} in this material: the {@code wall} prototype naming the cell, its
     * shape the cell's polygon, its mass from the cell's area (docs/resources.md, "Walls").
     */
    public Entity wall(Cell wallCell) {
        Entity wall = Prototypes.get("wall").withName(name + " wall").withCell(wallCell);
        return wall.withMaterial(name).withMass(mass(wallCell.area()));
    }
}
