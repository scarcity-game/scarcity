package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.pessimisticit.scarcity.world.model.mesh.Plane;
import java.util.ArrayList;
import java.util.List;

/**
 * One convex piece of an area's floor plan (docs/resources.md, "Cells"): {@code floor},
 * {@code wall} or {@code rock}, its {@code polygon}, one {@code neighbours} entry per edge, and for
 * a floor cell its {@code clearance}. A {@code wall} entity names its wall cell and takes its shape
 * from it. Cells change together, so they are made and changed by
 * {@code com.pessimisticit.scarcity.world.model.mesh.CellMesh}, which keeps them a tiling.
 */
@StoredResource
@JsonPropertyOrder({"resourceKind", "id", "version", "children", "kind", "polygon", "position", "neighbours",
        "clearance"})
public final class Cell extends Resource {

    /** What a cell is: open floor, solid rock touching floor (a wall names it), or solid rock touching none. */
    public enum Kind {
        @JsonProperty("floor") FLOOR,
        @JsonProperty("wall") WALL,
        @JsonProperty("rock") ROCK;

        /** Whether movers can be in it. */
        public boolean open() {
            return this == FLOOR;
        }
    }

    /**
     * The cell across one edge (null where the tiling ends) and, between two floor cells, the
     * edge's {@code width}: the widest disc that can cross it somewhere.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Neighbour(String cell, Double width) {
        public Neighbour {
            if (cell != null) {
                Ids.require(ResourceKind.CELL.prefix(), cell);
            }
            if (width != null) {
                Check.nonNegative(width, "width");
                if (cell == null) {
                    throw new IllegalArgumentException("width: only between two cells");
                }
            }
        }
    }

    private Kind kind;
    private List<Position> polygon;
    private Position position;
    private List<Neighbour> neighbours;
    private Double clearance;

    /** For Jackson. */
    private Cell() {
    }

    /**
     * A cell with these values, checked: {@code neighbours} one per edge (entry {@code i} the edge
     * from vertex {@code i} to the next), {@code clearance} for a floor cell only, widths only
     * between floor cells is the mesh's to keep.
     */
    public static Cell of(String id, Kind kind, List<Position> polygon, List<Neighbour> neighbours, Double clearance) {
        Cell cell = new Cell();
        cell.kind = kind;
        cell.polygon = polygon == null ? null : List.copyOf(polygon);
        cell.neighbours = neighbours == null ? null : new ArrayList<>(neighbours);
        cell.clearance = clearance;
        cell.validate();
        return id == null ? cell : cell.withId(id);
    }

    @Override
    public ResourceKind resourceKind() {
        return ResourceKind.CELL;
    }

    public Kind kind() {
        return kind;
    }

    /** The outline in area coordinates, counter-clockwise and convex; a vertex may lie on the line between its neighbours. */
    public List<Position> polygon() {
        return polygon;
    }

    /** The polygon's centroid. */
    public Position position() {
        return position;
    }

    /** One per edge, in the polygon's order. */
    public List<Neighbour> neighbours() {
        return neighbours;
    }

    /** Floor cells only: metres from the centroid to the nearest non-floor cell, or the tiling's end; null otherwise. */
    public Double clearance() {
        return clearance;
    }

    /** The enclosed area, in square metres. */
    public double area() {
        return ConvexPolygon.area(polygon);
    }

    /** Metres from {@code p} to the cell's polygon: 0 inside it or on its edge. */
    public double distanceTo(Position p) {
        return Plane.toPolygon(p, polygon);
    }

    /** The same cell with its version kept and these values, for the mesh. */
    public Cell with(Kind newKind, List<Position> newPolygon, List<Neighbour> newNeighbours, Double newClearance) {
        Cell copy = copy();
        copy.kind = newKind;
        copy.polygon = List.copyOf(newPolygon);
        copy.neighbours = new ArrayList<>(newNeighbours);
        copy.clearance = newClearance;
        copy.validate();
        return copy;
    }

    @Override
    protected void validate() {
        super.validate();
        Check.required(kind, "kind");
        polygon = ConvexPolygon.check(polygon);
        position = ConvexPolygon.centroid(polygon);
        Check.required(neighbours, "neighbours");
        if (neighbours.size() != polygon.size()) {
            throw new IllegalArgumentException("neighbours: one per edge, " + polygon.size() + ", got " + neighbours.size());
        }
        neighbours.forEach(n -> Check.required(n, "neighbours"));
        neighbours = List.copyOf(neighbours);
        if (kind.open()) {
            Check.required(clearance, "clearance");
            Check.nonNegative(clearance, "clearance");
        } else if (clearance != null) {
            throw new IllegalArgumentException("clearance: only a floor cell has one");
        }
    }
}
