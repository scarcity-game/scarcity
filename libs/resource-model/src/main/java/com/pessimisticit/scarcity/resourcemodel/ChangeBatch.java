package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One notification: {@code {"area","changes":[{"id","type","version","patch","children","deleted"}],"logSeq","traceparent"}}.
 * {@code patch} is the new state (or a delta); null patch and null traceparent are omitted.
 * A change is {id,type,version,patch,children,deleted}: children only when the child list changed (full new list);
 * a destroyed resource has only id,type,deleted:true; null patch/children and false deleted are omitted.
 * logSeq is optional, omitted when null.
 */
@JsonPropertyOrder({"area", "changes", "logSeq", "traceparent"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeBatch(String area, List<Change> changes, Long logSeq, String traceparent) {

    public ChangeBatch {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public ChangeBatch(String area, List<Change> changes, String traceparent) {
        this(area, changes, null, traceparent);
    }

    @JsonPropertyOrder({"id","type","version","patch","children","deleted"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String id, ResourceType type,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) long version, ObjectNode patch, List<String> children,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean deleted) {
        public Change {
            children = children == null ? null : List.copyOf(children);
        }

        public Change(String id, ResourceType type, long version, ObjectNode patch) {
            this(id, type, version, patch, null, false);
        }

        public static Change changed(String id, ResourceType type, long version, ObjectNode patch) {
            return new Change(id, type, version, patch, null, false);
        }

        public static Change changed(String id, ResourceType type, long version, ObjectNode patch, List<String> children) {
            return new Change(id, type, version, patch, children, false);
        }

        public static Change deleted(String id, ResourceType type) {
            return new Change(id, type, 0, null, null, true);
        }
    }

    public ChangeBatch withTraceparent(String tp) {
        return new ChangeBatch(area, changes, logSeq, tp);
    }
}
