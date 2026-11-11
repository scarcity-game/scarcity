package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The resource envelope. JSON has exactly the keys id, type, version, children, state, in
 * that order. {@code children} is derived from the store (ids, creation order).
 */
@JsonPropertyOrder({"id", "type", "version", "children", "state"})
public record Resource(String id, ResourceType type, long version, List<String> children, ObjectNode state) {

    public Resource {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** A new resource with a fresh id, to pass to {@link ResourceStore#create}. */
    public static Resource newResource(ResourceType type, ObjectNode state) {
        return new Resource(ResourceIds.newId(type), type, 1, List.of(), state);
    }
}
