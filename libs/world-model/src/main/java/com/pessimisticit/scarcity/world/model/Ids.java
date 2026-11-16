package com.pessimisticit.scarcity.world.model;

import java.util.UUID;

/**
 * Mints and checks type-prefixed resource ids ({@code entity:<uuid>}): the
 * prefix, a colon, then a lowercase canonical UUID.
 */
public final class Ids {

    private Ids() {
    }

    /** Returns {@code id} if it is a well-formed id with the given prefix ("entity"); throws otherwise. */
    public static String require(String prefix, String id) {
        Check.text(id, "id");
        String start = prefix + ":";
        if (!id.startsWith(start) || !isCanonicalUuid(id.substring(start.length()))) {
            throw new IllegalArgumentException("id: not a well-formed " + prefix + " id: " + id);
        }
        return id;
    }

    /** A fresh id for a resource of {@code kind}. */
    public static String newId(ResourceKind kind) {
        return kind.prefix() + ":" + UUID.randomUUID();
    }

    /** The kind of a well-formed id; throws IllegalArgumentException if the prefix or uuid is bad. */
    public static ResourceKind kindOf(String id) {
        Check.text(id, "id");
        for (ResourceKind kind : ResourceKind.values()) {
            String start = kind.prefix() + ":";
            if (id.startsWith(start) && isCanonicalUuid(id.substring(start.length()))) {
                return kind;
            }
        }
        throw new IllegalArgumentException("malformed resource id: " + id);
    }

    public static boolean isValid(String id) {
        try {
            kindOf(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCanonicalUuid(String uuid) {
        try {
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
