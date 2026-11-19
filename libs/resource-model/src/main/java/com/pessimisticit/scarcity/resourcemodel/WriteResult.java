package com.pessimisticit.scarcity.resourcemodel;

import com.pessimisticit.scarcity.world.model.ResourceKind;

/**
 * Outcome of a write, with what a publisher needs to build a {@link ChangeBatch}.
 *
 * <ul>
 *   <li>create: {@code version}=1 (or the version a stored resource carried in); {@code parentVersion} = parent's new version (null if no parent).</li>
 *   <li>updateState: {@code version} = new version; {@code parentVersion} null (parent untouched).</li>
 *   <li>delete: {@code version} = the deleted resource's last version (it no longer exists);
 *       {@code parentVersion} = parent's new version (null if no parent).</li>
 * </ul>
 * {@code areaId} is the batch routing key (see resources.sql); null for games.
 */
public record WriteResult(String id, ResourceKind resourceKind, long version, String areaId, String parentId,
        Long parentVersion) {
}
