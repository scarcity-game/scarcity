package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;

/**
 * Entity states without a position (a projectile's, a debris's), kept as
 * JSON until they are created somewhere. A template is checked when its
 * params are read, so a bad one can't start the action that carries it.
 */
final class Templates {

    private static final Position ORIGIN = new Position(0.0, 0.0);

    private Templates() {
    }

    /** A copy of {@code template}, checked to make a valid state once placed. */
    static ObjectNode checked(ObjectNode template, String key) {
        Check.required(template, key);
        try {
            at(template, ORIGIN);
        } catch (WorldJsonException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage(), e);
        }
        return template.deepCopy();
    }

    /** The state of {@code template} placed at {@code position}. */
    static EntityState at(ObjectNode template, Position position) {
        ObjectNode state = template.deepCopy();
        state.set("position", WorldJson.toTree(position));
        return WorldJson.fromJson(state, EntityState.class);
    }
}
