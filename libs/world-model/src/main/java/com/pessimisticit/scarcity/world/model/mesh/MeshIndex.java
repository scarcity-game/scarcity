package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The mutable state of a {@link CellMesh}: each cell's kind and polygon, every directed edge's
 * owner (an edge's twin is the reverse edge, so the cell across edge a to b owns b to a), the cells
 * at each vertex, and the derived {@code clearance} and {@code width}. Vertices are compared exactly.
 */
final class MeshIndex {

    /** A directed edge of one cell, counter-clockwise round it. */
    record Edge(Position from, Position to) {
    }

    /** An undirected edge, the key of its {@code width}: its ends in a fixed order. */
    record Link(Position a, Position b) {
        static Link of(Position p, Position q) {
            int order = Double.compare(p.x(), q.x()) != 0 ? Double.compare(p.x(), q.x()) : Double.compare(p.y(), q.y());
            return order <= 0 ? new Link(p, q) : new Link(q, p);
        }
    }

    /** A floor-to-floor edge: its two cells and its ends. */
    record Opening(String cell, String across, Position from, Position to) {
    }

    private final Map<String, Cell.Kind> kinds;
    private final Map<String, List<Position>> polygons;
    private final Map<String, Long> versions;
    private final Map<Edge, String> owners;
    private final Map<Position, Set<String>> byVertex;
    private final Map<String, Double> clearances;
    private final Map<Link, Double> widths;

    MeshIndex() {
        this.kinds = new TreeMap<>();
        this.polygons = new HashMap<>();
        this.versions = new HashMap<>();
        this.owners = new HashMap<>();
        this.byVertex = new HashMap<>();
        this.clearances = new HashMap<>();
        this.widths = new HashMap<>();
    }

    /** Adds a cell, owning its edges; throws if another cell owns one of them already (the cells overlap). */
    void add(String id, Cell.Kind kind, List<Position> polygon, Long version) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Edge edge = new Edge(polygon.get(i), polygon.get((i + 1) % n));
            String owner = owners.putIfAbsent(edge, id);
            if (owner != null) {
                throw new IllegalArgumentException("polygon: " + id + " and " + owner + " share the edge "
                        + edge.from() + " to " + edge.to() + " the same way round: they overlap");
            }
            byVertex.computeIfAbsent(polygon.get(i), v -> new HashSet<>()).add(id);
        }
        kinds.put(id, kind);
        polygons.put(id, List.copyOf(polygon));
        if (version != null) {
            versions.put(id, version);
        }
    }

    /** Removes a cell and its edges; its clearance goes, the widths stay for the caller to prune. */
    void remove(String id) {
        List<Position> polygon = polygons.remove(id);
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            owners.remove(new Edge(polygon.get(i), polygon.get((i + 1) % n)));
            Set<String> at = byVertex.get(polygon.get(i));
            at.remove(id);
            if (at.isEmpty()) {
                byVertex.remove(polygon.get(i));
            }
        }
        kinds.remove(id);
        versions.remove(id);
        clearances.remove(id);
    }

    boolean contains(String id) {
        return kinds.containsKey(id);
    }

    /** Every cell's id, ascending. */
    SortedSet<String> ids() {
        return new TreeSet<>(kinds.keySet());
    }

    Cell.Kind kind(String id) {
        return kinds.get(id);
    }

    void setKind(String id, Cell.Kind kind) {
        kinds.put(id, kind);
    }

    boolean open(String id) {
        return id != null && kinds.get(id).open();
    }

    List<Position> polygon(String id) {
        return polygons.get(id);
    }

    /** The cell's centroid, computed as {@code Cell} computes its position. */
    Position centroid(String id) {
        return Plane.centroid(polygons.get(id));
    }

    Long version(String id) {
        return versions.get(id);
    }

    /** The cell across edge {@code i} of {@code id}, or null where the tiling ends. */
    String across(String id, int i) {
        List<Position> polygon = polygons.get(id);
        return owners.get(new Edge(polygon.get((i + 1) % polygon.size()), polygon.get(i)));
    }

    /** The owner of the directed edge {@code from} to {@code to}, or null. */
    String owner(Position from, Position to) {
        return owners.get(new Edge(from, to));
    }

    /** The cells across its edges, without repeats or nulls. */
    Set<String> edgeNeighbours(String id) {
        Set<String> found = new TreeSet<>();
        for (int i = 0; i < polygons.get(id).size(); i++) {
            String other = across(id, i);
            if (other != null) {
                found.add(other);
            }
        }
        return found;
    }

    /** The other cells that share a vertex with it (its edge neighbours among them). */
    Set<String> touching(String id) {
        Set<String> found = new TreeSet<>();
        for (Position v : polygons.get(id)) {
            found.addAll(byVertex.get(v));
        }
        found.remove(id);
        return found;
    }

    Double clearance(String id) {
        return clearances.get(id);
    }

    void setClearance(String id, Double value) {
        if (value == null) {
            clearances.remove(id);
        } else {
            clearances.put(id, value);
        }
    }

    Double width(Link link) {
        return widths.get(link);
    }

    void setWidth(Link link, double value) {
        widths.put(link, value);
    }

    /** Drops the widths of edges that are no longer between two floor cells. */
    void pruneWidths() {
        Set<Link> live = new HashSet<>();
        openings().forEach(o -> live.add(Link.of(o.from(), o.to())));
        widths.keySet().retainAll(live);
    }

    /** Every floor-to-floor edge once, from the cell with the lower id. */
    List<Opening> openings() {
        List<Opening> found = new ArrayList<>();
        for (String id : kinds.keySet()) {
            if (kinds.get(id).open()) {
                found.addAll(openingsOf(id));
            }
        }
        return found;
    }

    /** The floor-to-floor edges of a floor cell to a cell with a higher id. */
    List<Opening> openingsOf(String id) {
        List<Opening> found = new ArrayList<>();
        List<Position> polygon = polygons.get(id);
        for (int i = 0; i < polygon.size(); i++) {
            String other = across(id, i);
            if (open(other) && id.compareTo(other) < 0) {
                found.add(new Opening(id, other, polygon.get(i), polygon.get((i + 1) % polygon.size())));
            }
        }
        return found;
    }

    /** The cell as a resource, its neighbours and derived values as they are now. */
    Cell cell(String id) {
        List<Position> polygon = polygons.get(id);
        int n = polygon.size();
        List<Cell.Neighbour> neighbours = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String other = across(id, i);
            Double width = open(id) && open(other) ? widths.get(Link.of(polygon.get(i), polygon.get((i + 1) % n))) : null;
            neighbours.add(new Cell.Neighbour(other, width));
        }
        Cell cell = Cell.of(id, kinds.get(id), polygon, neighbours, open(id) ? clearances.get(id) : null);
        Long version = versions.get(id);
        return version == null ? cell : cell.withVersion(version);
    }
}
