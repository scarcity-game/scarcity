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

    public LogChange(String id, String type, String op, Long version, ObjectNode state) {
        this.id = id;
        this.type = type;
        this.op = op;
        this.version = version;
        this.state = state;
    }

    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("type", type);
        node.put("op", op);
        if (version != null) {
            node.put("version", version);
        } else {
            node.putNull("version");
        }
        node.set("state", state);
        return node;
    }
}