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
}
