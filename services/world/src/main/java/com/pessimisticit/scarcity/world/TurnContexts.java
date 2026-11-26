package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What an intelligence service is told of a turn (docs/actions.md, "Intelligence services"): the
 * entity, the cells whose polygon comes within range of it, the entities whose position is within
 * range, and the walls of those cells.
 */
final class TurnContexts {

    private TurnContexts() {
    }

    /**
     * The request for {@code entity}'s turn since {@code since} in {@code snapshot}, an area as committed.
     *
     * @return empty when the entity isn't in the area any more: the turn is stale
     */
    static Optional<TurnRequest> of(AreaSnapshot snapshot, String entity, double since, double range) {
        return of(snapshot.area().id(), snapshot.entities(), snapshot.cells().values(), entity, since, range);
    }

    /**
     * The request for {@code entity}'s turn in {@code areaView}, the area as an advance has it now
     * (a turn decided inside the advance): its entities' versions are as loaded.
     */
    static Optional<TurnRequest> of(AreaView areaView, String entity, double since, double range) {
        return of(areaView.areaId(), areaView.entities().values(), areaView.cells().values(), entity, since, range);
    }

    private static Optional<TurnRequest> of(String areaId, Collection<Entity> all, Collection<Cell> allCells,
            String entity, double since, double range) {
        Optional<Entity> found = all.stream().filter(e -> e.id().equals(entity)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Entity self = found.get();
        Position at = self.position();
        List<Cell> cells = new ArrayList<>();
        Set<String> cellIds = new TreeSet<>();
        for (Cell cell : allCells) {
            if (cell.distanceTo(at) <= range) {
                cells.add(cell);
                cellIds.add(cell.id());
            }
        }
        List<Entity> entities = new ArrayList<>();
        for (Entity e : all) {
            if (!e.id().equals(entity) && inRange(e, at, range, cellIds)) {
                entities.add(e);
            }
        }
        return Optional.of(new TurnRequest(areaId, entity, self.version(), since, range, self, entities, cells));
    }

    private static boolean inRange(Entity e, Position at, double range, Set<String> cellIds) {
        if (e.cell() != null) {
            return cellIds.contains(e.cell());
        }
        return e.position() != null && e.position().distanceTo(at) <= range;
    }
}
