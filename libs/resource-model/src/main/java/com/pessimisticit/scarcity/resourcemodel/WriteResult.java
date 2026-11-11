package com.pessimisticit.scarcity.resourcemodel;

/**
 * Outcome of a write, with what a publisher needs to build a {@link ChangeBatch}.
 *
 * <ul>
 *   <li>create: {@code version}=1; {@code parentVersion} = parent's new version (null if no parent).</li>
 *   <li>updateState: {@code version} = new version; {@code parentVersion} null (parent untouched).</li>
 *   <li>delete: {@code version} = the deleted resource's last version (it no longer exists);
 *       {@code parentVersion} = parent's new version (null if no parent).</li>
 * </ul>
 * {@code areaId} is the batch routing key (see resources.sql); null for games.
 */
public record WriteResult(String id, ResourceType type, long version, String areaId, String parentId,
        Long parentVersion) {
}
