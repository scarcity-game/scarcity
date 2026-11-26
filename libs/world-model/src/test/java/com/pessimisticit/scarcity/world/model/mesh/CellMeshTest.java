package com.pessimisticit.scarcity.world.model.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Meshes of square cells of 1 m: a room of open cells inside solid ones. */
class CellMeshTest {

    private static Supplier<String> ids(long seed) {
        Random rng = new Random(seed);
        return () -> Ids.newId(ResourceKind.CELL, rng);
    }

    private static List<Position> square(int col, int row) {
        return List.of(new Position(col, row), new Position(col + 1.0, row),
                new Position(col + 1.0, row + 1.0), new Position(col, row + 1.0));
    }

    /** {@code cols} x {@code rows} squares; open where {@code open} says. */
    private static CellMesh grid(int cols, int rows, Supplier<String> ids, OpenAt open) {
        List<CellMesh.Piece> pieces = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                pieces.add(new CellMesh.Piece(square(col, row), open.at(col, row)));
            }
        }
        return CellMesh.generate(pieces, ids);
    }

    private interface OpenAt {
        boolean at(int col, int row);
    }

    /** 7 x 6 squares, a 3 x 2 room at columns 2..4, rows 2..3. */
    private static CellMesh room(Supplier<String> ids) {
        return grid(7, 6, ids, (c, r) -> c >= 2 && c <= 4 && r >= 2 && r <= 3);
    }

    private static Cell at(CellMesh mesh, double x, double y) {
        return mesh.cells().stream().filter(c -> Plane.inside(new Position(x, y), c.polygon())
                && Plane.toPolygon(new Position(x, y), c.polygon()) == 0.0
                && c.position().distanceTo(new Position(x, y)) < 0.5).findFirst().orElseThrow();
    }

    @Test
    void kindsFollowTheRoom() {
        CellMesh mesh = room(ids(1));
        assertThat(mesh.cells()).hasSize(42);
        assertThat(mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.FLOOR)).hasSize(6);
        // the ring round the room (5 x 4 less the room) is wall, the outer ring rock
        assertThat(mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.WALL)).hasSize(14);
        assertThat(mesh.cells().stream().filter(c -> c.kind() == Cell.Kind.ROCK)).hasSize(22);
        assertThat(at(mesh, 1.5, 1.5).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(at(mesh, 0.5, 0.5).kind()).isEqualTo(Cell.Kind.ROCK);
    }

    @Test
    void clearanceAndWidthInTheRoom() {
        CellMesh mesh = room(ids(2));
        Cell middle = at(mesh, 3.5, 2.5);
        assertThat(middle.clearance()).isCloseTo(0.5, within(1e-9));
        // the room is 3 m wide and 2 m high: a disc of 2 m crosses between the two rows
        Cell above = at(mesh, 3.5, 3.5);
        int up = edgeTo(middle, above.id());
        assertThat(middle.neighbours().get(up).width()).isCloseTo(2.0, within(1e-6));
        // and between columns, at the room's mid-height
        Cell right = at(mesh, 4.5, 2.5);
        assertThat(middle.neighbours().get(edgeTo(middle, right.id())).width()).isCloseTo(2.0, within(1e-6));
        // no width toward a wall
        Cell below = at(mesh, 3.5, 1.5);
        assertThat(middle.neighbours().get(edgeTo(middle, below.id())).width()).isNull();
        assertThat(below.clearance()).isNull();
    }

    @Test
    void aCorridorOneCellWideIsOneMetreWide() {
        CellMesh mesh = grid(7, 3, ids(3), (c, r) -> r == 1 && c >= 1 && c <= 5);
        Cell a = at(mesh, 2.5, 1.5);
        Cell b = at(mesh, 3.5, 1.5);
        assertThat(a.neighbours().get(edgeTo(a, b.id())).width()).isCloseTo(1.0, within(1e-6));
        assertThat(a.clearance()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void storedCellsReadBackAsTheSameMeshAndJson() {
        CellMesh mesh = room(ids(4));
        CellMesh again = CellMesh.of(mesh.cells());
        assertThat(again.cells()).isEqualTo(mesh.cells());
        Cell cell = mesh.cells().get(0);
        assertThat(WorldJson.fromJson(WorldJson.toJson(cell), com.pessimisticit.scarcity.world.model.Resource.class))
                .isEqualTo(cell);
        assertThat(WorldJson.toJson(at(mesh, 3.5, 2.5))).contains("\"kind\":\"floor\"", "\"clearance\":0.5");
    }

    @Test
    void closingAndOpeningACellUpdatesWhatIsNearAndRestoresIt() {
        CellMesh mesh = room(ids(5));
        List<Cell> before = mesh.cells();
        Cell middle = at(mesh, 3.5, 2.5);
        MeshChange closed = mesh.close(middle.id());
        assertThat(closed.changed()).extracting(Cell::id).contains(middle.id());
        assertThat(mesh.get(middle.id()).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(at(mesh, 2.5, 2.5).clearance()).isCloseTo(0.5, within(1e-9));
        assertSameDerived(mesh, mesh.recomputed());
        assertInvariants(mesh);
        MeshChange opened = mesh.open(middle.id());
        assertThat(opened.changed()).isNotEmpty();
        assertThat(mesh.cells()).isEqualTo(before);
    }

    @Test
    void openingAWallTurnsTheRockBehindItIntoWall() {
        CellMesh mesh = room(ids(6));
        Cell wall = at(mesh, 1.5, 2.5);
        Cell rock = at(mesh, 0.5, 2.5);
        mesh.open(wall.id());
        assertThat(mesh.get(rock.id()).kind()).isEqualTo(Cell.Kind.WALL);
        assertThat(mesh.get(wall.id()).kind()).isEqualTo(Cell.Kind.FLOOR);
        assertSameDerived(mesh, mesh.recomputed());
        mesh.close(wall.id());
        assertThat(mesh.get(rock.id()).kind()).isEqualTo(Cell.Kind.ROCK);
    }

    @Test
    void splittingPutsTheCutsEndsIntoTheNeighbours() {
        Supplier<String> ids = ids(7);
        CellMesh mesh = room(ids);
        Cell middle = at(mesh, 3.5, 2.5);
        Cell above = at(mesh, 3.5, 3.5);
        MeshChange split = mesh.split(middle.id(), new Position(3.25, 0.0), new Position(3.25, 9.0), ids);
        assertThat(split.deleted()).containsExactly(middle.id());
        assertThat(split.created()).hasSize(2).allMatch(c -> c.kind() == Cell.Kind.FLOOR);
        assertThat(mesh.get(above.id()).polygon()).hasSize(5).contains(new Position(3.25, 3.0));
        assertThat(split.changed()).extracting(Cell::id).contains(above.id());
        assertInvariants(mesh);
        assertSameDerived(mesh, mesh.recomputed());
    }

    @Test
    void aSplitThatMissesOrLeavesASliverIsRefused() {
        Supplier<String> ids = ids(8);
        CellMesh mesh = room(ids);
        String middle = at(mesh, 3.5, 2.5).id();
        assertThatThrownBy(() -> mesh.split(middle, new Position(9, 0), new Position(9, 1), ids))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line:");
        assertThatThrownBy(() -> mesh.split(middle, new Position(3.005, 0), new Position(3.005, 9), ids))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("line:");
        assertThat(mesh.get(middle)).isNotNull();
    }

    @Test
    void randomChangesKeepTheTilingAndMatchAFullRecomputation() {
        Supplier<String> ids = ids(9);
        CellMesh mesh = grid(9, 8, ids, (c, r) -> c >= 2 && c <= 6 && r >= 2 && r <= 5);
        Random rng = new Random(10);
        int splits = 0;
        for (int step = 0; step < 120; step++) {
            List<Cell> cells = mesh.cells();
            Cell cell = cells.get(rng.nextInt(cells.size()));
            int what = rng.nextInt(3);
            if (what == 0) {
                mesh.open(cell.id());
            } else if (what == 1) {
                mesh.close(cell.id());
            } else {
                splits += trySplit(mesh, cell, rng, ids) ? 1 : 0;
            }
            assertInvariants(mesh);
            assertSameDerived(mesh, mesh.recomputed());
        }
        assertThat(splits).as("splits that went through").isGreaterThan(15);
        assertThat(mesh.cells().stream().filter(c -> c.polygon().size() > 4)).as("cells that gained a vertex").isNotEmpty();
    }

    private static boolean trySplit(CellMesh mesh, Cell cell, Random rng, Supplier<String> ids) {
        Position c = cell.position();
        double angle = rng.nextDouble() * Math.PI;
        Position a = new Position(c.x() - Math.cos(angle), c.y() - Math.sin(angle));
        Position b = new Position(c.x() + Math.cos(angle), c.y() + Math.sin(angle));
        try {
            mesh.split(cell.id(), a, b, ids);
            return true;
        } catch (IllegalArgumentException refused) {
            assertThat(refused).hasMessageStartingWith("line:");
            return false;
        }
    }

    private static int edgeTo(Cell cell, String other) {
        for (int i = 0; i < cell.neighbours().size(); i++) {
            if (other.equals(cell.neighbours().get(i).cell())) {
                return i;
            }
        }
        throw new AssertionError(cell.id() + " has no edge to " + other);
    }

    /**
     * Every edge has a twin on its neighbour (or lies on the grid's outside), areas add up to the
     * grid's, and solid cells are wall exactly when they share a vertex with floor.
     */
    private static void assertInvariants(CellMesh mesh) {
        Map<String, Cell> byId = new HashMap<>();
        mesh.cells().forEach(c -> byId.put(c.id(), c));
        double total = 0.0;
        for (Cell cell : mesh.cells()) {
            total += cell.area();
            List<Position> p = cell.polygon();
            for (int i = 0; i < p.size(); i++) {
                Position from = p.get(i);
                Position to = p.get((i + 1) % p.size());
                String other = cell.neighbours().get(i).cell();
                if (other == null) {
                    assertThat(onOutside(from) && onOutside(to)).as("an outside edge of " + cell.id()).isTrue();
                } else {
                    assertThat(hasEdge(byId.get(other), to, from, cell.id()))
                            .as("twin of " + from + " to " + to + " in " + other).isTrue();
                }
            }
            if (!cell.kind().open()) {
                boolean touchesFloor = mesh.cells().stream().anyMatch(o -> o.kind().open()
                        && o.polygon().stream().anyMatch(cell.polygon()::contains));
                assertThat(cell.kind()).isEqualTo(touchesFloor ? Cell.Kind.WALL : Cell.Kind.ROCK);
            }
        }
        assertThat(total).as("the cells tile the grid").isCloseTo(gridArea(mesh), within(1e-9));
    }

    private static double gridArea(CellMesh mesh) {
        double maxX = 0.0;
        double maxY = 0.0;
        for (Cell cell : mesh.cells()) {
            for (Position v : cell.polygon()) {
                maxX = Math.max(maxX, v.x());
                maxY = Math.max(maxY, v.y());
            }
        }
        return maxX * maxY;
    }

    private static boolean onOutside(Position p) {
        return p.x() == 0.0 || p.y() == 0.0 || p.x() == 9.0 || p.y() == 8.0 || p.x() == 7.0 || p.y() == 6.0;
    }

    private static boolean hasEdge(Cell cell, Position from, Position to, String across) {
        List<Position> p = cell.polygon();
        for (int i = 0; i < p.size(); i++) {
            if (p.get(i).equals(from) && p.get((i + 1) % p.size()).equals(to)) {
                return across.equals(cell.neighbours().get(i).cell());
            }
        }
        return false;
    }

    private static void assertSameDerived(CellMesh mesh, CellMesh fresh) {
        assertThat(mesh.cells()).hasSameSizeAs(fresh.cells());
        for (Cell cell : mesh.cells()) {
            Cell expected = fresh.get(cell.id());
            assertThat(cell.kind()).isEqualTo(expected.kind());
            assertThat(cell.polygon()).isEqualTo(expected.polygon());
            if (expected.clearance() == null) {
                assertThat(cell.clearance()).as("clearance of " + cell.id()).isNull();
            } else {
                assertThat(cell.clearance()).as("clearance of " + cell.id()).isCloseTo(expected.clearance(), within(1e-9));
            }
            for (int i = 0; i < cell.neighbours().size(); i++) {
                Cell.Neighbour got = cell.neighbours().get(i);
                Cell.Neighbour want = expected.neighbours().get(i);
                assertThat(got.cell()).isEqualTo(want.cell());
                if (want.width() == null) {
                    assertThat(got.width()).as("width " + i + " of " + cell.id()).isNull();
                } else {
                    assertThat(got.width()).as("width " + i + " of " + cell.id()).isCloseTo(want.width(), within(1e-9));
                }
            }
        }
    }
}
