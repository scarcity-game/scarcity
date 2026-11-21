package com.pessimisticit.scarcity.resourcemodel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One notification (docs/resources.md, "Change notifications"):
 * {@code {"area","reload","changes":[...],"fromLogSeq","logSeq","part","parts","traceparent"}}.
 * Changes are deltas ({@link Change}); a batch never carries a resource's whole state.
 * A <em>reload</em> batch has no changes and tells subscribers to load the area afresh.
 * {@code part}/{@code parts} are set only when the publisher split the batch. Null keys and
 * an empty change list are omitted; {@code reload} is {@code true} or absent.
 */
@JsonPropertyOrder({"area", "reload", "changes", "fromLogSeq", "logSeq", "part", "parts", "traceparent"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeBatch(
        String area,
        Boolean reload,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Change> changes,
        Long fromLogSeq,
        Long logSeq,
        Integer part,
        Integer parts,
        String traceparent) {

    public ChangeBatch {
        Objects.requireNonNull(area, "area");
        changes = changes == null ? List.of() : List.copyOf(changes);
        reload = Boolean.TRUE.equals(reload) ? Boolean.TRUE : null;
        if (reload != null && !changes.isEmpty()) {
            throw new IllegalArgumentException("a reload carries no changes");
        }
    }

    /** One advance's changes, before any split. */
    public static ChangeBatch of(String area, List<Change> changes, Long fromLogSeq, Long logSeq,
            String traceparent) {
        return new ChangeBatch(area, null, changes, fromLogSeq, logSeq, null, null, traceparent);
    }

    /** Load {@code area} afresh: it was made in this advance, or its batch was too big to send. */
    public static ChangeBatch reload(String area, Long logSeq, String traceparent) {
        return new ChangeBatch(area, true, List.of(), null, logSeq, null, null, traceparent);
    }

    /** Whether this is a reload: {@code reload} is {@code true}, never {@code false} (absent instead). */
    public boolean reloading() {
        return reload != null;
    }

    public ChangeBatch withTraceparent(String tp) {
        return new ChangeBatch(area, reload, changes, fromLogSeq, logSeq, part, parts, tp);
    }

    /** The same batch with {@code newChanges} (the publisher's, some made stubs). */
    public ChangeBatch withChanges(List<Change> newChanges) {
        return new ChangeBatch(area, reload, newChanges, fromLogSeq, logSeq, part, parts, traceparent);
    }

    /** This batch's {@code changes} as part {@code n} of {@code total} (1-based). */
    public ChangeBatch part(List<Change> partChanges, int n, int total) {
        return new ChangeBatch(area, reload, partChanges, fromLogSeq, logSeq, n, total, traceparent);
    }

    /**
     * One change, as the JSON it has on the wire. Always {@code resourceKind} and {@code id}; then one of
     * <ul>
     * <li>an <em>update</em>: {@code version} and any of {@code set}, {@code unset},
     * {@code childrenAdded}, {@code childrenRemoved}, applied to {@code version - 1};</li>
     * <li><em>created</em>: {@code version, created: true}; a subscriber {@code GET}s it;</li>
     * <li><em>deleted</em>: {@code deleted: true}, no version;</li>
     * <li>a <em>stub</em>: {@code version} only, an update too big to send.</li>
     * </ul>
     */
    public record Change(ObjectNode json) {

        /** The kinds of change. */
        public enum Kind { UPDATE, CREATED, DELETED, STUB }

        /** Keys of a resource that are never in {@code set}: the envelope and the child list. */
        private static final Set<String> ENVELOPE = Set.of("resourceKind", "id", "version", "children");
        private static final List<String> DELTA_KEYS = List.of("set", "unset", "childrenAdded", "childrenRemoved");

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

        /** Number-aware node comparison: 0 when equal, 3 equal to 3.0. */
        public static int compareNodes(JsonNode a, JsonNode b) {
            if (a.isNumber() && b.isNumber()) {
                return a.decimalValue().compareTo(b.decimalValue());
            }
            return a.equals(b) ? 0 : 1;
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Change fromJson(ObjectNode json) {
            return new Change(json);
        }

        /**
         * The update from {@code before} to {@code after} (both stored, {@code after} at the new version):
         * each top-level key whose value differs, whole, in {@code set}; keys that are gone in {@code unset}.
         * A null value counts as absent. The child list is never diffed: pass what was added and removed.
         */
        public static Change update(Resource before, Resource after, List<String> childrenAdded,
                List<String> childrenRemoved) {
            after.requireStored();
            if (!Objects.equals(before.id(), after.id())) {
                throw new IllegalArgumentException("an update of " + before.id() + " to " + after.id());
            }
            ObjectNode b = (ObjectNode) WorldJson.toTree(before);
            ObjectNode a = (ObjectNode) WorldJson.toTree(after);
            return update(after.resourceKind(), after.id(), after.version(), setOf(b, a), unsetOf(b, a),
                    childrenAdded, childrenRemoved);
        }

        /** The own keys of {@code after} whose value is new or differs from {@code before}'s. */
        private static ObjectNode setOf(ObjectNode before, ObjectNode after) {
            ObjectNode set = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : after.properties()) {
                if (ownValue(e.getKey(), e.getValue()) && !same(before.get(e.getKey()), e.getValue())) {
                    set.set(e.getKey(), e.getValue());
                }
            }
            return set;
        }

        /** The own keys of {@code before} that {@code after} lacks or has null. */
        private static List<String> unsetOf(ObjectNode before, ObjectNode after) {
            List<String> unset = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : before.properties()) {
                JsonNode now = after.get(e.getKey());
                if (ownValue(e.getKey(), e.getValue()) && (now == null || now.isNull())) {
                    unset.add(e.getKey());
                }
            }
            return unset;
        }

        private static boolean ownValue(String key, JsonNode value) {
            return !ENVELOPE.contains(key) && !value.isNull();
        }

        private static boolean same(JsonNode old, JsonNode value) {
            return old != null && !old.isNull() && value.equals(Change::compareNodes, old);
        }

        /**
         * An update with the given parts; empty or null parts are left out, except that an update with
         * nothing in it carries {@code "set":{}}, so it never reads as a stub.
         */
        public static Change update(ResourceKind kind, String id, long version, ObjectNode set, List<String> unset,
                List<String> childrenAdded, List<String> childrenRemoved) {
            ObjectNode node = head(kind, id);
            node.put("version", version);
            if (set != null && !set.isEmpty()) {
                node.set("set", set);
            }
            putIds(node, "unset", unset);
            putIds(node, "childrenAdded", childrenAdded);
            putIds(node, "childrenRemoved", childrenRemoved);
            if (node.size() == 3) {
                node.putObject("set");
            }
            return new Change(node);
        }

        public static Change created(String id, ResourceKind kind, long version) {
            ObjectNode node = head(kind, id);
            node.put("version", version);
            node.put("created", true);
            return new Change(node);
        }

        public static Change deleted(String id, ResourceKind kind) {
            ObjectNode node = head(kind, id);
            node.put("deleted", true);
            return new Change(node);
        }

        private static ObjectNode head(ResourceKind kind, String id) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("resourceKind", kind.prefix());
            node.put("id", Objects.requireNonNull(id, "id"));
            return node;
        }

        private static void putIds(ObjectNode node, String key, List<String> ids) {
            if (ids != null && !ids.isEmpty()) {
                ArrayNode array = node.putArray(key);
                ids.forEach(array::add);
            }
        }

        @JsonValue
        public ObjectNode json() {
            return json;
        }

        public String id() {
            return json.get("id").asString();
        }

        public ResourceKind resourceKind() {
            return ResourceKind.of(json.get("resourceKind").asString());
        }

        public Kind kind() {
            if (json.path("deleted").asBoolean(false)) {
                return Kind.DELETED;
            }
            if (json.path("created").asBoolean(false)) {
                return Kind.CREATED;
            }
            for (String key : DELTA_KEYS) {
                if (json.has(key)) {
                    return Kind.UPDATE;
                }
            }
            return Kind.STUB;
        }

        /** The resource's version; 0 for a deleted one. */
        public long version() {
            return json.path("version").asLong(0);
        }

        /** {@code set}, empty when absent. */
        public ObjectNode set() {
            JsonNode s = json.get("set");
            return s instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
        }

        public List<String> unset() {
            return ids("unset");
        }

        public List<String> childrenAdded() {
            return ids("childrenAdded");
        }

        public List<String> childrenRemoved() {
            return ids("childrenRemoved");
        }

        private List<String> ids(String key) {
            List<String> out = new ArrayList<>();
            JsonNode a = json.get(key);
            if (a != null) {
                Iterator<JsonNode> it = a.values().iterator();
                it.forEachRemaining(n -> out.add(n.asString()));
            }
            return out;
        }

        /**
         * {@code resource}'s JSON (at {@code version - 1}, with {@code children}) with this update applied; a
         * client does the same. Only for an update.
         */
        public ObjectNode applyTo(ObjectNode resource) {
            if (kind() != Kind.UPDATE) {
                throw new IllegalStateException("only an update applies; this is " + kind());
            }
            ObjectNode out = resource.deepCopy();
            out.put("version", version());
            set().properties().forEach(e -> out.set(e.getKey(), e.getValue()));
            unset().forEach(out::remove);
            List<String> removed = childrenRemoved();
            List<String> added = childrenAdded();
            if (!removed.isEmpty() || !added.isEmpty()) {
                ArrayNode children = JsonNodeFactory.instance.arrayNode();
                JsonNode old = out.path("children");
                old.values().forEach(n -> {
                    if (!removed.contains(n.asString())) {
                        children.add(n);
                    }
                });
                added.forEach(children::add);
                out.set("children", children);
            }
            return out;
        }

        /** The same update without its body: the client fetches the resource. */
        public Change stub() {
            if (kind() == Kind.DELETED || kind() == Kind.CREATED) {
                return this;
            }
            ObjectNode node = head(resourceKind(), id());
            node.put("version", version());
            return new Change(node);
        }
    }
}
