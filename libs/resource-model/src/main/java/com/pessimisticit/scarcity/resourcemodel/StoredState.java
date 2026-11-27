package com.pessimisticit.scarcity.resourcemodel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;

/**
 * The {@code state} column: a resource's JSON without the keys the table
 * keeps in columns ({@code id}, {@code version}, {@code children}) and the
 * {@code resourceKind} (the {@code type} column). It still holds
 * {@code entityKind} and {@code prototype}, which pick the Java class.
 */
final class StoredState {

    private StoredState() {
    }

    static String of(Resource resource) {
        ObjectNode node = (ObjectNode) WorldJson.toTree(resource);
        node.remove(List.of("resourceKind", "id", "version", "children", "childCounts"));
        return node.toString();
    }

    /** Rebuilds the typed resource from its columns. */
    static Resource read(String id, ResourceKind kind, long version, List<String> children, String stateJson) {
        ObjectNode node = (ObjectNode) WorldJson.fromJson(stateJson, JsonNode.class);
        ObjectNode full = node.objectNode();
        full.put("resourceKind", kind.prefix());
        full.put("id", id);
        full.put("version", version);
        full.putArray("children").addAll(children.stream().map(node::textNode).toList());
        full.setAll(node);
        return WorldJson.fromJson(full, Resource.class);
    }
}
