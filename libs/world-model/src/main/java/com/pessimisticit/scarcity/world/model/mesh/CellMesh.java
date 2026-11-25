package com.pessimisticit.scarcity.world.model.mesh;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.mesh.MeshIndex.Link;
import com.pessimisticit.scarcity.world.model.mesh.MeshIndex.Opening;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * An area's cells as one tiling (docs/resources.md, "Cells"): made from generated pieces or read
 * from stored cells, changed by opening, closing or splitting a cell, each change returning the
 * cells it changed, created and deleted, with {@code clearance} and {@code width} kept current
 * nearby. Not thread-safe. Cell versions are the caller's: a changed cell keeps its version, a
 * created one has none.
 *
 * <p>A {@code wall} entity names its cell, and the mesh knows nothing of entities: when a change
 * creates or deletes a {@code wall} cell, or changes a cell's kind to or from {@code wall}, the
 * caller makes or removes the wall.
 */
public final class CellMesh {

    /** Leeway, in metres, when deciding whether a change is near enough to a value to recompute it. */
    private static final double NEAR = 1e-9;

    /** A piece of a generated floor plan: a convex counter-clockwise polygon, open or solid. */
    public record Piece(List<Position> polygon, boolean open) {
    }

    private final MeshIndex index;
    private final Blocked blocked;
    private final Map<String, Cell> current;

    private CellMesh() {
        this.index = new MeshIndex();
        this.blocked = new Blocked(index);
        this.current = new TreeMap<>();
    }

    /**
     * The mesh of a generated floor plan: one cell per piece, ids from {@code newIds}. Open pieces
     * are {@code floor}; solid ones are {@code wall} when they share a vertex with an open one and
     * {@code rock} otherwise. Pieces must tile without overlap and share vertices exactly, with no
     * T-junction.
     */
    public static CellMesh generate(List<Piece> pieces, Supplier<String> newIds) {
        CellMesh mesh = new CellMesh();
        for (Piece piece : pieces) {
            mesh.index.add(newIds.get(), piece.open() ? Cell.Kind.FLOOR : Cell.Kind.ROCK, piece.polygon(), null);
        }
        for (String id : mesh.index.ids()) {
            mesh.classify(id);
        }
        mesh.recomputeAll();
        return mesh;
    }

    /** The mesh of stored cells, their {@code clearance} and {@code width} as stored. */
    public static CellMesh of(Collection<Cell> cells) {
        CellMesh mesh = new CellMesh();
        for (Cell cell : cells) {
            mesh.index.add(cell.id(), cell.kind(), cell.polygon(), cell.stored() ? cell.version() : null);
        }
        for (Cell cell : cells) {
            mesh.index.setClearance(cell.id(), cell.clearance());
            List<Position> polygon = cell.polygon();
            for (int i = 0; i < polygon.size(); i++) {
                Double width = cell.neighbours().get(i).width();
                if (width != null) {
                    mesh.index.setWidth(Link.of(polygon.get(i), polygon.get((i + 1) % polygon.size())), width);
                }
            }
        }
        mesh.index.ids().forEach(id -> mesh.current.put(id, mesh.index.cell(id)));
        return mesh;
    }

    /** Every cell, ascending by id. */
    public List<Cell> cells() {
        return List.copyOf(current.values());
    }

    /** The cell with this id, or null. */
    public Cell get(String id) {
        return current.get(id);
    }

    /** The same cells with every {@code clearance} and {@code width} computed afresh: to check a mesh. */
    public CellMesh recomputed() {
        CellMesh copy = of(cells());
        copy.recomputeAll();
        return copy;
    }

    /** Makes a solid cell {@code floor}; solid cells that now touch floor become {@code wall}. */
    public MeshChange open(String id) {
        require(id);
        if (index.open(id)) {
            return MeshChange.NONE;
        }
        Set<String> touched = derivedNear(index.polygon(id));
        index.setKind(id, Cell.Kind.FLOOR);
        touched.add(id);
        return afterKindChange(id, touched);
    }

    /** Makes a floor cell solid ({@code wall}, or {@code rock} if no floor touches it); walls left touching no floor become {@code rock}. */
    public MeshChange close(String id) {
        require(id);
        if (!index.open(id)) {
            return MeshChange.NONE;
        }
        Set<String> touched = derivedNear(index.polygon(id));
        index.setKind(id, Cell.Kind.ROCK);
        index.setClearance(id, null);
        touched.add(id);
        return afterKindChange(id, touched);
    }

    /**
     * Cuts a cell by the line through {@code a} and {@code b} into two of its kind, with ids from
     * {@code newIds}. The cut's ends become vertices of the cells across the edges it crosses, so
     * the tiling has no T-junction; the blocked region doesn't change, so only the pieces' own
     * values are computed.
     *
     * @throws IllegalArgumentException as {@link Split#of} does
     */
    public MeshChange split(String id, Position a, Position b, Supplier<String> newIds) {
        require(id);
        Split split = Split.of(index.polygon(id), a, b);
        Cell.Kind kind = index.kind(id);
        Set<String> touched = new TreeSet<>(index.edgeNeighbours(id));
        for (Split.Crossing crossing : split.crossed()) {
            String across = index.owner(crossing.to(), crossing.from());
            if (across != null) {
                List<Position> grown = Split.insert(index.polygon(across), crossing);
                Cell.Kind acrossKind = index.kind(across);
                Long version = index.version(across);
                Double clearance = index.clearance(across);
                index.remove(across);
                index.add(across, acrossKind, grown, version);
                index.setClearance(across, clearance);
            }
        }
        index.remove(id);
        String left = newIds.get();
        String right = newIds.get();
        index.add(left, kind, split.left(), null);
        index.add(right, kind, split.right(), null);
        for (String piece : List.of(left, right)) {
            classify(piece);
            if (kind.open()) {
                index.setClearance(piece, blocked.clearance(piece, index.centroid(piece)));
                index.openingsOf(piece).forEach(this::measure);
                openingsInto(piece).forEach(this::measure);
            }
            touched.add(piece);
        }
        index.pruneWidths();
        current.remove(id);
        return collect(touched, List.of(id));
    }

    private void require(String id) {
        if (!index.contains(id)) {
            throw new IllegalArgumentException("cell: no " + id + " in the mesh");
        }
    }

    /**
     * The floor cells whose {@code clearance}, and both cells of each opening whose {@code width},
     * a change of the blocked region at {@code region} may change, before it changes; each value
     * is recomputed after. A value can change only if the region is no farther than it.
     */
    private Set<String> derivedNear(List<Position> region) {
        Set<String> near = new TreeSet<>();
        for (String id : index.ids()) {
            Double clearance = index.clearance(id);
            if (clearance != null && Plane.toPolygon(index.centroid(id), region) <= clearance + NEAR) {
                near.add(id);
            }
        }
        for (Opening o : index.openings()) {
            Double width = index.width(Link.of(o.from(), o.to()));
            if (width == null || Plane.segmentToPolygon(o.from(), o.to(), region) <= width / 2.0 + NEAR) {
                near.add(o.cell());
                near.add(o.across());
            }
        }
        return near;
    }

    private MeshChange afterKindChange(String id, Set<String> touched) {
        classify(id);
        for (String other : index.touching(id)) {
            classify(other);
            touched.add(other);
        }
        index.pruneWidths();
        for (String cell : touched) {
            if (index.open(cell)) {
                index.setClearance(cell, blocked.clearance(cell, index.centroid(cell)));
                index.openingsOf(cell).forEach(this::measure);
                openingsInto(cell).forEach(this::measure);
            }
        }
        return collect(touched, List.of());
    }

    /** A solid cell is a wall when it shares a vertex with a floor cell, else rock. */
    private void classify(String id) {
        if (index.open(id)) {
            return;
        }
        boolean wall = index.touching(id).stream().anyMatch(index::open);
        index.setKind(id, wall ? Cell.Kind.WALL : Cell.Kind.ROCK);
    }

    /** The openings into {@code id} from floor cells with lower ids (the others are its own). */
    private List<Opening> openingsInto(String id) {
        return index.edgeNeighbours(id).stream()
                .filter(other -> index.open(other) && other.compareTo(id) < 0)
                .flatMap(other -> index.openingsOf(other).stream().filter(o -> o.across().equals(id)))
                .toList();
    }

    private void measure(Opening o) {
        index.setWidth(Link.of(o.from(), o.to()), blocked.width(o.cell(), o.across(), o.from(), o.to()));
    }

    private void recomputeAll() {
        for (String id : index.ids()) {
            index.setClearance(id, index.open(id) ? blocked.clearance(id, index.centroid(id)) : null);
        }
        index.pruneWidths();
        index.openings().forEach(this::measure);
        current.clear();
        index.ids().forEach(id -> current.put(id, index.cell(id)));
    }

    /** The cells among {@code touched} that now differ from before, or are new; and {@code deleted}. */
    private MeshChange collect(Set<String> touched, List<String> deleted) {
        Map<String, Cell> changed = new TreeMap<>();
        Map<String, Cell> created = new TreeMap<>();
        for (String id : touched) {
            if (!index.contains(id)) {
                continue;
            }
            Cell now = index.cell(id);
            Cell before = current.put(id, now);
            if (before == null) {
                created.put(id, now);
            } else if (!before.equals(now)) {
                changed.put(id, now);
            }
        }
        return new MeshChange(List.copyOf(changed.values()), List.copyOf(created.values()), List.copyOf(deleted));
    }
}
