package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Faction;
import com.pessimisticit.scarcity.world.model.Factions;
import com.pessimisticit.scarcity.world.model.Game;
import java.util.Map;

/**
 * The faction relations an area plays by (docs/resources.md, "Factions"): its game's
 * ({@link Game#relations()}), read in the caller's transaction; the standard ones for an area whose
 * game can't be read.
 */
class GameFactions {

    private final ResourceStore resourceStore;

    GameFactions(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    Map<String, Faction> of(String areaId) {
        return resourceStore.parentOf(areaId).flatMap(resourceStore::get)
                .filter(Game.class::isInstance).map(g -> ((Game) g).relations())
                .orElse(Factions.standard());
    }
}
