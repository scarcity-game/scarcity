package com.pessimisticit.scarcity.resourcemodel;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.List;

/** Builds changes as flat JSON for tests that care about the wire shape, not about a stored resource. */
final class Changes {

    private Changes() {
    }

    /** A change of an entity: the three keys of a stub, then {@code body}'s keys (null: none). */
    static ChangeBatch.Change change(String id, long version, ObjectNode body) {
        return change(id, ResourceKind.ENTITY, version, body, null);
    }

    /** A change of any kind, with a {@code children} array when {@code children} is not null. */
    static ChangeBatch.Change change(String id, ResourceKind kind, long version, ObjectNode body,
            List<String> children) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("resourceKind", kind.prefix());
        node.put("id", id);
        node.put("version", version);
        if (children != null) {
            ArrayNode kids = node.putArray("children");
            children.forEach(kids::add);
        }
        if (body != null) {
            node.setAll(body);
        }
        return new ChangeBatch.Change(node);
    }
}
