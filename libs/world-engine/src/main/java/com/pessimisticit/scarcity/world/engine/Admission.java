package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.engine.IntentRejected.Reason;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.rules.Actor;
import com.pessimisticit.scarcity.world.model.rules.BoundAction;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;

/** Whether an intent may start (docs/actions.md, "Intents"), in the order of its status codes. */
final class Admission {

    private Admission() {
    }

    /**
     * @param up the entity that is up (docs/actions.md, "Turns"); only it may act
     * @throws IntentRejected with the first rule that fails
     */
    static BoundAction<?> check(Intent intent, World world, Catalogue catalogue, String up) {
        String id = intent.entity();
        if (!world.exists(id)) {
            throw new IntentRejected(Reason.UNKNOWN_ENTITY, "entity: no entity " + id + " in " + world.areaId());
        }
        EntityState state = world.peek(id);
        if (state.controller() == null) {
            throw new IntentRejected(Reason.NOT_CONTROLLED, "entity: " + id + " has no controller");
        }
        Long version = world.version(id);
        if (intent.ifVersion() != null && !intent.ifVersion().equals(version)) {
            throw new IntentRejected(Reason.STALE, "ifVersion: " + id + " is at version " + version
                    + ", not " + intent.ifVersion());
        }
        if (!state.idle()) {
            throw new IntentRejected(Reason.BUSY, "entity: " + id + " already has an action ("
                    + state.action().type() + " until " + state.action().end() + ")");
        }
        if (!id.equals(up)) {
            throw new IntentRejected(Reason.NOT_UP, "entity: " + id + " is pending; " + up + " is up");
        }
        try {
            return catalogue.start(intent.action(), new Actor(id, state, world));
        } catch (InvalidAction e) {
            throw new IntentRejected(Reason.INVALID, e.getMessage());
        }
    }
}
