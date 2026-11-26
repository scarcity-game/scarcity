package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import com.pessimisticit.scarcity.world.model.mesh.CellMesh;
import com.pessimisticit.scarcity.world.model.mesh.MeshChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What {@code fill} and {@code dig} share: change one cell's kind through a {@link CellMesh} of the
 * view's cells, write every cell the mesh changed, and keep the walls in step: a cell that became a
 * {@code wall} gets a wall entity in the level's material, one that stopped being a wall loses its.
 */
final class CellEdit {

    /** The result both effects log. */
    record Result(String cell, String kindBefore, String kindAfter, List<String> changed, List<String> wallsCreated,
            List<String> wallsDestroyed) {
    }

    /** The material of walls made when the level has none to copy. */
    static final String DEFAULT_MATERIAL = "granite";

    private CellEdit() {
    }

    /** Applies {@code change} to {@code cellId}, which must be of kind {@code from}; if not, the result says it was skipped. */
    static void run(EffectContext ctx, String cellId, Cell.Kind from, Function<CellMesh, Function<String, MeshChange>> change) {
        Cell cell = ctx.cells().get(cellId);
        if (cell == null) {
            ctx.result(new Skipped("no such cell"));
            return;
        }
        if (cell.kind() != from) {
            ctx.result(new Skipped("cell is " + cell.kind().name().toLowerCase() + ", not " + from.name().toLowerCase()));
            return;
        }
        Map<String, Cell> before = Map.copyOf(ctx.cells());
        MeshChange meshChange = change.apply(CellMesh.of(before.values())).apply(cellId);
        List<String> created = new ArrayList<>();
        List<String> destroyed = new ArrayList<>();
        Material material = levelMaterial(ctx);
        for (Cell next : meshChange.changed()) {
            Cell old = before.get(next.id());
            ctx.updateCell(next);
            if (next.kind() == Cell.Kind.WALL && old.kind() != Cell.Kind.WALL) {
                created.add(ctx.create(ctx.areaId(), material.wall(ctx.cells().get(next.id()))));
            } else if (old.kind() == Cell.Kind.WALL && next.kind() != Cell.Kind.WALL) {
                wallOf(ctx, next.id()).ifPresent(id -> {
                    ctx.destroy(id);
                    destroyed.add(id);
                });
            }
        }
        ctx.result(new Result(cellId, cell.kind().name().toLowerCase(), ctx.cells().get(cellId).kind().name().toLowerCase(),
                meshChange.changed().stream().map(Cell::id).toList(), created, destroyed));
    }

    /** The result of an edit that did nothing. */
    record Skipped(String skipped) {
    }

    private static Optional<String> wallOf(EffectContext ctx, String cellId) {
        return ctx.entities().entrySet().stream().filter(e -> cellId.equals(e.getValue().cell())).map(Map.Entry::getKey)
                .findFirst();
    }

    /** The material of the first wall (by id) that has one, else granite. */
    private static Material levelMaterial(EffectContext ctx) {
        return ctx.entities().values().stream()
                .filter(e -> e.cell() != null && e.material() != null)
                .map(e -> Materials.get(e.material()))
                .findFirst()
                .orElseGet(() -> Materials.get(DEFAULT_MATERIAL));
    }
}
