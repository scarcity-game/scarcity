package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One notification: {@code {"area","changes":[{"id","type","version","patch"}],"traceparent"}}.
 * {@code patch} is the new state (or a delta); null patch and null traceparent are omitted.
 */
@JsonPropertyOrder({"area", "changes", "traceparent"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeBatch(String area, List<Change> changes, String traceparent) {

    public ChangeBatch {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    @JsonPropertyOrder({"id", "type", "version", "patch"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String id, ResourceType type, long version, ObjectNode patch) {
    }

    public ChangeBatch withTraceparent(String tp) {
        return new ChangeBatch(area, changes, tp);
    }
}
