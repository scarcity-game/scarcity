package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class LogChange {
    public final String id;
    public final String type;
    public final String op;
    public final ObjectNode state;
    public Long version;
    public final String parent;

    public LogChange(String id, String type, String op, Long version, ObjectNode state) {
        this(id, type, op, version, state, null);
    }

    public LogChange(String id, String type, String op, Long version, ObjectNode state, String parent) {
        this.id = id;
        this.type = type;
        this.op = op;
        this.version = version;
        this.state = state;
        this.parent = parent;
    }

    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if ("destroyed".equals(op)) {
            node.put("id", id);
            node.put("type", type);
            node.put("op", op);
            return node;
        }
        node.put("id", id);
        node.put("type", type);
        node.put("op", op);
        if (version != null) {
            node.put("version", version);
        } else {
            node.putNull("version");
        }
        node.set("state", state);
        if (parent != null) {
            node.put("parent", parent);
        }
        return node;
    }
}
