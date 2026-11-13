package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class LogEntry {
    public final long seq;
    public final double time;
    public final String kind;
    public final Long cause;
    public final long root;
    public final String resource;
    public final String reason;
    public final JsonNode action;
    public final ArrayNode draws;
    public final List<LogChange> changes;
    public final JsonNode effect;
    public final JsonNode result;

    public LogEntry(long seq, double time, String kind, Long cause, long root, String resource,
                    String reason, JsonNode action, ArrayNode draws, List<LogChange> changes) {
        this(seq, time, kind, cause, root, resource, reason, action, draws, changes, null, null);
    }

    public LogEntry(long seq, double time, String kind, Long cause, long root, String resource,
                    String reason, JsonNode action, ArrayNode draws, List<LogChange> changes,
                    JsonNode effect, JsonNode result) {
        this.seq = seq;
        this.time = time;
        this.kind = kind;
        this.cause = cause;
        this.root = root;
        this.resource = resource;
        this.reason = reason;
        this.action = action;
        this.draws = draws;
        this.changes = changes;
        this.effect = effect;
        this.result = result;
    }

    public ObjectNode body() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (reason != null) {
            node.put("reason", reason);
        }
        if (action != null) {
            node.set("action", action);
        }
        node.set("draws", draws);
        ArrayNode changesArray = JsonNodeFactory.instance.arrayNode();
        for (LogChange change : changes) {
            changesArray.add(change.toJson());
        }
        node.set("changes", changesArray);
        if (effect != null) {
            node.set("effect", effect);
        }
        if (result != null) {
            node.set("result", result);
        }
        return node;
    }
}