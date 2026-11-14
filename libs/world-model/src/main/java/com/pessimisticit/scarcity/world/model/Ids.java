package com.pessimisticit.scarcity.world.model;

import java.util.UUID;

/**
 * Checks type-prefixed resource ids ({@code entity:<uuid>}), with the same
 * rules as {@code ResourceIds} in libs/resource-model, which the model does
 * not depend on: the prefix, a colon, then a lowercase canonical UUID.
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

    private static boolean isCanonicalUuid(String uuid) {
        try {
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
