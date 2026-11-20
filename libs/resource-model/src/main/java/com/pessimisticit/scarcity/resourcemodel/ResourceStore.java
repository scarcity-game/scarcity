package com.pessimisticit.scarcity.resourcemodel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;

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

    /** The id of the resource's parent; empty if it is missing or has none (a game). */
    Optional<String> parentOf(String id);

    /**
     * Resources of {@code type} whose state has {@code state->>key = value} for every filter entry.
     * Ordered by creation. {@code size} must be 1..{@value #MAX_SIZE}, {@code page} >= 0.
     */
    default Page<Resource> list(ResourceKind type, Map<String, String> filter, int page, int size) {
        return list(type, null, filter, page, size);
    }

    /**
     * As {@link #list(ResourceKind, Map, int, int)}, limited to resources inside the game {@code gameId}
     * (null: all games): the game itself, its areas, their entities. One query.
     * Throws IllegalArgumentException if {@code gameId} is not a well-formed game id; an unknown game
     * gives an empty page.
     */
    default Page<Resource> list(ResourceKind type, String gameId, Map<String, String> filter, int page, int size) {
        return list(type, gameId, filter, List.of(), page, size);
    }

    /**
     * As {@link #list(ResourceKind, String, Map, int, int)}, and also only resources whose state has each key
     * in {@code has} as a top-level key with a non-null value (presence filter). Keys are checked like filter
     * keys (IllegalArgumentException).
     */
    Page<Resource> list(ResourceKind type, String gameId, Map<String, String> filter, List<String> has,
            int page, int size);

    /** Direct children of {@code parentId}, optionally of one type. Empty page if none (or no parent). */
    Page<Resource> children(String parentId, ResourceKind type, int page, int size);

    /**
     * Inserts and bumps the parent's version once. The version is 1, or, if {@code resource} is stored (has an id and
     * a version), that version: an entity that moved from another area keeps its sequence. {@code children()} is ignored.
     */
    WriteResult create(Resource resource, String parentId);

    /** Replaces the state with {@code resource}'s (by its id), version+1. Throws {@link ResourceNotFoundException}. */
    WriteResult update(Resource resource);

    /** Deletes a childless resource and bumps its parent once. Throws IllegalStateException if it has children. */
    WriteResult delete(String id);

    /**
     * One tick's child changes with <b>one</b> version bump of the parent: deletes the {@code deletes}
     * (each must be a childless direct child of {@code parentId}), then inserts the {@code creates}
     * (in the order given; {@code children()} is ignored; the version is 1, or a stored resource's own), and, if {@code newParent} is not
     * null, replaces the parent's state. The parent is row-locked first and ends at version+1.
     * <p>
     * Throws {@link ResourceNotFoundException} if the parent is missing; {@link IllegalArgumentException}
     * if a delete id is not a child of the parent, an id appears twice, an id is both created and
     * deleted (rejected, not a no-op), or there is nothing to do; {@link IllegalStateException} if a
     * deleted child has children. Validation happens before any write, so a rejected call changes
     * nothing even if the caller's transaction is not rolled back.
     */
    ChildrenWrite applyChildren(String parentId, java.util.List<Resource> creates, java.util.List<String> deletes,
            Resource newParent);
}
