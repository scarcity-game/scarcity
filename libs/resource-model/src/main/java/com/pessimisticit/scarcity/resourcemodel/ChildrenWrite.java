package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;

/**
 * Outcome of {@link ResourceStore#applyChildren}: the parent's new version and child list, plus the
 * per-child results (a created child has {@code version}=1; a deleted one its last version). Every
 * entry in {@code created} and {@code deleted} has {@code parentVersion} equal to {@code parentVersion}.
 */
public record ChildrenWrite(String parentId, long parentVersion, List<String> children,
        List<WriteResult> created, List<WriteResult> deleted) {
}
