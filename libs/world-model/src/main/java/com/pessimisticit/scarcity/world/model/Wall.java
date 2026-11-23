package com.pessimisticit.scarcity.world.model;

import java.util.List;

/**
 * A wall (docs/resources.md, "Walls"; docs/decisions.md, 2026-11-21): a solid
 * convex polygon that doesn't move and takes no actions. {@code polygon} is its
 * outline in area coordinates, counter-clockwise, and {@code material} picks
 * how a client draws it. It has no {@code radius}, {@code mass}, {@code speed},
 * {@code actions}, {@code controller}, {@code behaviour} or children, and is
 * neither {@code overlap} nor {@code damageable}.
 *
 * <p>{@code position} is the polygon's centroid, computed here whenever the
 * polygon or the position is set; a stored one that disagrees is rewritten.
 * A prototype has no polygon yet, so no position ({@link #of}).
 */
@StoredResource
public final class Wall extends Entity {

    private String material;
    private List<Position> polygon;

    /** For Jackson. */
    private Wall() {
    }

    /** A wall of this prototype ({@code stone-wall}) with this outline. */
    public static Wall of(String prototype, List<Position> polygon) {
        if (!(Prototypes.get(prototype) instanceof Wall wall)) {
            throw new IllegalArgumentException("prototype: not a wall: " + prototype);
        }
        return wall.withPolygon(polygon);
    }

    @Override
    public EntityKind entityKind() {
        return EntityKind.WALL;
    }

    public String material() {
        return material;
    }

    /** The outline; null on a prototype. */
    public List<Position> polygon() {
        return polygon;
    }

    /** The same wall with this outline, and its centroid as the position. */
    public Wall withPolygon(List<Position> newPolygon) {
        Wall copy = copy();
        copy.polygon = newPolygon == null ? null : List.copyOf(newPolygon);
        copy.validate();
        return copy;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.text(material, "material");
        rejectKeysOfAMover();
        if (polygon != null) {
            polygon = ConvexPolygon.check(polygon);
            placeAt(ConvexPolygon.centroid(polygon));
        }
    }

    private void rejectKeysOfAMover() {
        rejectPresent(mass(), "mass");
        rejectPresent(speed(), "speed");
        rejectPresent(radius(), "radius");
        rejectPresent(hp(), "hp");
        rejectPresent(actionInProgress(), "actionInProgress");
        rejectPresent(behaviour(), "behaviour");
        rejectPresent(controller(), "controller");
        if (!actions().isEmpty()) {
            throw new IllegalArgumentException("actions: a wall takes no actions");
        }
        if (children() != null && !children().isEmpty()) {
            throw new IllegalArgumentException("children: a wall has no children");
        }
        if (overlap()) {
            throw new IllegalArgumentException("overlap: a wall is always an obstacle");
        }
        if (damageable()) {
            throw new IllegalArgumentException("damageable: a wall can't be damaged yet");
        }
    }

    private static void rejectPresent(Object value, String key) {
        if (value != null) {
            throw new IllegalArgumentException(key + ": a wall has no " + key);
        }
    }

    @Override
    public Wall requireStored() {
        Check.required(polygon, "polygon");
        super.requireStored();
        return this;
    }

    /** A wall has an outline instead of a mass and a radius. */
    @Override
    public boolean physical() {
        return false;
    }
}
