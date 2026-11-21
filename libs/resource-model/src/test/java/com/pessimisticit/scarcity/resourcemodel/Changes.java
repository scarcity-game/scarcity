package com.pessimisticit.scarcity.resourcemodel;

import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** Builds changes and batches for tests that care about the wire shape, not about a stored resource. */
final class Changes {

    private Changes() {
    }

    /** An update of an entity: {@code body} as its {@code set} (null: an empty update). */
    static ChangeBatch.Change change(String id, long version, ObjectNode body) {
        return change(id, ResourceKind.ENTITY, version, body, null);
    }

    /** An update of any kind, with {@code body} as {@code set} and {@code children} as {@code childrenAdded}. */
    static ChangeBatch.Change change(String id, ResourceKind kind, long version, ObjectNode body,
            List<String> children) {
        return ChangeBatch.Change.update(kind, id, version, body, null, children, null);
    }

    /** One advance's batch with no logSeqs. */
    static ChangeBatch batch(String area, List<ChangeBatch.Change> changes, String traceparent) {
        return ChangeBatch.of(area, changes, null, null, traceparent);
    }

    /** One advance's batch from {@code logSeq - 1} to {@code logSeq}. */
    static ChangeBatch batch(String area, List<ChangeBatch.Change> changes, Long logSeq, String traceparent) {
        return ChangeBatch.of(area, changes, logSeq == null ? null : logSeq - 1, logSeq, traceparent);
    }
}
