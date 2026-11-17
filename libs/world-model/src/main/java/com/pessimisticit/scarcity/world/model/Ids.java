package com.pessimisticit.scarcity.world.model;

import java.util.UUID;
import java.util.random.RandomGenerator;

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

    /**
     * An id for a resource of {@code kind} drawn from {@code rng}: a version 4 UUID from two longs of
     * the generator, so a seeded generator makes the same ids (docs/action-types.md, "Effects").
     */
    public static String newId(ResourceKind kind, RandomGenerator rng) {
        long msb = (rng.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (rng.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return kind.prefix() + ":" + new UUID(msb, lsb);
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
