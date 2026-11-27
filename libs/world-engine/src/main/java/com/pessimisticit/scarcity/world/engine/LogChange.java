package com.pessimisticit.scarcity.world.engine;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Objects;

/**
 * One item of an entry's {@code changes} (docs/log.md, "An entry").
 *
 * @param type {@code "area"}, {@code "entity"}, {@code "item"} or {@code "cell"}
 * @param version the version the advance writes; null when none is
 *     written (created or changed, then destroyed, in one advance) and for
 *     {@code destroyed}
 * @param state the resulting {@code Area} or {@code Entity}; null for {@code destroyed}
 * @param parent the resource a {@code created} one was added to, and an item's holder in a
 *     {@code snapshot}; null otherwise
 */
public record LogChange(String id, String type, Op op, Long version, Resource state, String parent) {

    /** {@code changes[].op}. */
    public enum Op {
        SNAPSHOT("snapshot"), CREATED("created"), CHANGED("changed"), DESTROYED("destroyed");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public LogChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(op, "op");
    }

    static LogChange changed(String id, Entity state) {
        return new LogChange(id, "entity", Op.CHANGED, null, state, null);
    }

    static LogChange created(String id, Entity state, String parent) {
        return new LogChange(id, "entity", Op.CREATED, null, state, parent);
    }

    static LogChange cellChanged(String id, Cell state) {
        return new LogChange(id, "cell", Op.CHANGED, null, state, null);
    }

    static LogChange destroyed(String id) {
        return new LogChange(id, "entity", Op.DESTROYED, null, null, null);
    }

    static LogChange itemChanged(String id, Item state) {
        return new LogChange(id, "item", Op.CHANGED, null, state, null);
    }

    static LogChange itemCreated(String id, Item state, String parent) {
        return new LogChange(id, "item", Op.CREATED, null, state, parent);
    }

    static LogChange itemDestroyed(String id) {
        return new LogChange(id, "item", Op.DESTROYED, null, null, null);
    }

    /** The same change with {@code state}, keeping a {@code created} a {@code created}. */
    LogChange withState(Resource newState) {
        return new LogChange(id, type, op, version, newState, parent);
    }

    LogChange withVersion(Long newVersion) {
        Resource stamped = state == null || newVersion == null ? state : state.withId(id).withVersion(newVersion);
        return new LogChange(id, type, op, newVersion, stamped, parent);
    }

    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("type", type);
        node.put("op", op.wire());
        if (op == Op.DESTROYED) {
            return node;
        }
        if (version == null) {
            node.putNull("version");
        } else {
            node.put("version", version);
        }
        node.set("state", WorldJson.toObject(state));
        if (parent != null) {
            node.put("parent", parent);
        }
        return node;
    }
}
