package com.pessimisticit.scarcity.resourcemodel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One notification: {@code {"area","changes":[...],"logSeq","traceparent"}}. Each change is flat
 * resource JSON (docs/resources.md): the whole new resource, a <em>tombstone</em>
 * {@code {resourceKind,id,deleted:true}}, or, when a resource is too big for one notification, a
 * <em>stub</em> {@code {resourceKind,id,version}} that tells the client to fetch it.
 * Null logSeq and traceparent are omitted.
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

    /** One changed resource, as the JSON it has on the wire. */
    public record Change(ObjectNode json) {

        /** The keys of a stub: {@code resourceKind}, {@code id}, {@code version}. */
        private static final int STUB_KEYS = 3;

        public Change {
            Objects.requireNonNull(json, "json");
        }

        /** Equal when the JSON is, with 3 equal to 3L: a number read back from a notification is an int. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Change that && json.equals(Change::compareNodes, that.json);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id(), version());
        }

        private static int compareNodes(JsonNode a, JsonNode b) {
            if (a.isNumber() && b.isNumber()) {
                return a.decimalValue().compareTo(b.decimalValue());
            }
            return a.equals(b) ? 0 : 1;
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Change fromJson(ObjectNode json) {
            return new Change(json);
        }

        /** The whole resource, which must be stored (id and version set). */
        public static Change of(Resource resource) {
            resource.requireStored();
            return new Change((ObjectNode) WorldJson.toTree(resource));
        }

        public static Change deleted(String id, ResourceKind resourceKind) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("resourceKind", resourceKind.prefix());
            node.put("id", id);
            node.put("deleted", true);
            return new Change(node);
        }

        @JsonValue
        public ObjectNode json() {
            return json;
        }

        public String id() {
            return json.get("id").asText();
        }

        public ResourceKind resourceKind() {
            return ResourceKind.of(json.get("resourceKind").asText());
        }

        public boolean deleted() {
            return json.path("deleted").asBoolean(false);
        }

        /** The resource's version; 0 for a tombstone. */
        public long version() {
            return json.path("version").asLong(0);
        }

        /** Whether this is the resource itself: not a tombstone, not a stub. */
        public boolean hasBody() {
            return !deleted() && json.size() > STUB_KEYS;
        }

        /** The resource this change carries: empty for a tombstone or a stub. */
        public Optional<Resource> resource() {
            if (!hasBody()) {
                return Optional.empty();
            }
            return Optional.of(WorldJson.fromJson(json, Resource.class));
        }

        /** The same change without its body: the client fetches the resource. */
        public Change stub() {
            if (deleted()) {
                return this;
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("resourceKind", json.get("resourceKind").asText());
            node.put("id", json.get("id").asText());
            node.put("version", json.get("version").asLong());
            return new Change(node);
        }
    }

    public ChangeBatch withTraceparent(String tp) {
        return new ChangeBatch(area, changes, logSeq, tp);
    }
}
