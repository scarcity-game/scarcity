package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Objects;

/**
 * One item of an entry's {@code changes} (docs/log.md, "An entry").
 *
 * @param type {@code "area"} or {@code "entity"}
 * @param version the version the advance writes; null when none is
 *     written (created or changed, then destroyed, in one advance) and for
 *     {@code destroyed}
 * @param state the resulting {@code AreaState} or {@code EntityState}; null for {@code destroyed}
 * @param parent the resource a {@code created} one was added to; null otherwise
 */
public record LogChange(String id, String type, Op op, Long version, Record state, String parent) {

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

    static LogChange changed(String id, EntityState state) {
        return new LogChange(id, "entity", Op.CHANGED, null, state, null);
    }

    static LogChange created(String id, EntityState state, String parent) {
        return new LogChange(id, "entity", Op.CREATED, null, state, parent);
    }

    static LogChange destroyed(String id) {
        return new LogChange(id, "entity", Op.DESTROYED, null, null, null);
    }

    /** The same change with {@code state}, keeping a {@code created} a {@code created}. */
    LogChange withState(EntityState newState) {
        return new LogChange(id, type, op, version, newState, parent);
    }

    LogChange withVersion(Long newVersion) {
        return new LogChange(id, type, op, newVersion, state, parent);
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
