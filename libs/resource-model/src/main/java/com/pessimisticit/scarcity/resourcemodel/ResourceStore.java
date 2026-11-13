package com.pessimisticit.scarcity.resourcemodel;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Persistence of resources. Implementations join the caller's transaction and do not start
 * their own, so run writes (and the matching {@link ChangePublisher#publish}) in one.
 */
public interface ResourceStore {

    int MAX_SIZE = 200;

    Optional<Resource> get(String id);

    boolean exists(String id);

    /** The area a resource belongs to (an area: itself); empty if missing or area-less (games). */
    Optional<String> areaOf(String id);

    /**
     * Resources of {@code type} whose state has {@code state->>key = value} for every filter entry.
     * Ordered by creation. {@code size} must be 1..{@value #MAX_SIZE}, {@code page} >= 0.
     */
    Page<Resource> list(ResourceType type, Map<String, String> filter, int page, int size);

    /** Direct children of {@code parentId}, optionally of one type. Empty page if none (or no parent). */
    Page<Resource> children(String parentId, ResourceType type, int page, int size);

    /** Inserts at version 1 and bumps the parent's version once. {@code resource.children()}/version are ignored. */
    WriteResult create(Resource resource, String parentId);

    /** Replaces state, version+1. Throws {@link ResourceNotFoundException}. */
    WriteResult updateState(String id, ObjectNode newState);

    /** Deletes a childless resource and bumps its parent once. Throws IllegalStateException if it has children. */
    WriteResult delete(String id);

    /**
     * One tick's child changes with <b>one</b> version bump of the parent: deletes the {@code deletes}
     * (each must be a childless direct child of {@code parentId}), then inserts the {@code creates}
     * (in the order given; {@code children()}/version are ignored), and, if {@code newState} is not
     * null, replaces the parent's state. The parent is row-locked first and ends at version+1.
     * <p>
     * Throws {@link ResourceNotFoundException} if the parent is missing; {@link IllegalArgumentException}
     * if a delete id is not a child of the parent, an id appears twice, an id is both created and
     * deleted (rejected, not a no-op), or there is nothing to do; {@link IllegalStateException} if a
     * deleted child has children. Validation happens before any write, so a rejected call changes
     * nothing even if the caller's transaction is not rolled back.
     */
    ChildrenWrite applyChildren(String parentId, java.util.List<Resource> creates, java.util.List<String> deletes,
            ObjectNode newState);
}
