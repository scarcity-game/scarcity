package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.engine.IntentRejected.Reason;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.rules.Actor;
import com.pessimisticit.scarcity.world.model.rules.BoundAction;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;

/** Whether an intent may start (docs/actions.md, "Intents"), in the order of its status codes. */
final class Admission {

    private Admission() {
    }

    /**
     * @param activeEntity the active entity (docs/actions.md, "Turns"); only it may act
     * @throws IntentRejected with the first rule that fails
     */
    static BoundAction<?> check(Intent intent, AreaRun run, Catalogue catalogue, String activeEntity) {
        String id = intent.entity();
        if (!run.exists(id)) {
            throw new IntentRejected(Reason.UNKNOWN_ENTITY, "entity: no entity " + id + " in " + run.areaId());
        }
        Entity state = run.peek(id);
        if (state.controller() == null) {
            throw new IntentRejected(Reason.NOT_CONTROLLED, "entity: " + id + " has no controller");
        }
        Long version = run.version(id);
        if (intent.ifVersion() != null && !intent.ifVersion().equals(version)) {
            throw new IntentRejected(Reason.STALE, "ifVersion: " + id + " is at version " + version
                    + ", not " + intent.ifVersion());
        }
        if (!state.idle()) {
            throw new IntentRejected(Reason.BUSY, "entity: " + id + " already has an action ("
                    + state.actionInProgress().type() + " until " + state.actionInProgress().end() + ")");
        }
        if (!id.equals(activeEntity)) {
            throw new IntentRejected(Reason.NOT_ACTIVE, "entity: " + id + " is pending; " + activeEntity + " is the active entity");
        }
        try {
            return catalogue.start(intent.actionSpec(), new Actor(id, state, run));
        } catch (InvalidAction e) {
            throw new IntentRejected(Reason.INVALID, e.getMessage());
        }
    }
}
