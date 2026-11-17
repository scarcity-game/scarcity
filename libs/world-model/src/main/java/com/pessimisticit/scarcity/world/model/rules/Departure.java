package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import java.util.Objects;

/**
 * An entity that left an area by stairs in an advance (docs/actions.md,
 * "Crossing areas"). The engine removed it from the area; the caller carries
 * it to the other area in the same transaction.
 *
 * @param entity the traveller as it left, idle
 * @param fromStairs the stairs it took, in the area it left
 * @param toStairs the paired stairs it arrives at; for a level that doesn't
 *     exist yet, the id those stairs are made with
 * @param toArea the id the new level is made with, or null when the paired
 *     stairs already exist (the caller finds their area)
 */
public record Departure(Entity entity, String fromStairs, String toStairs, String toArea) {

    public Departure {
        Objects.requireNonNull(entity, "entity");
        Ids.require("entity", fromStairs);
        Ids.require("entity", toStairs);
        if (toArea != null) {
            Ids.require("area", toArea);
        }
    }

    /** Whether the level below is made for this crossing. */
    public boolean made() {
        return toArea != null;
    }
}
