package com.pessimisticit.scarcity.resourcemodel;

import java.util.UUID;

/** Mint and parse type-prefixed ids ({@code area:<uuid>}). */
public final class ResourceIds {

    private ResourceIds() {
    }

    public static String newId(ResourceType type) {
        return type.prefix() + UUID.randomUUID();
    }

    /** Type of a well-formed id; throws IllegalArgumentException if the prefix or uuid is bad. */
    public static ResourceType typeOf(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("malformed resource id: " + id);
        }
        ResourceType type;
        String uuid = id.substring(colon + 1);
        try {
            type = ResourceType.fromWire(id.substring(0, colon));
            if (!UUID.fromString(uuid).toString().equals(uuid)) {
                throw new IllegalArgumentException("bad uuid");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed resource id: " + id);
        }
        return type;
    }

    public static boolean isValid(String id) {
        try {
            typeOf(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True if {@code id} is well formed and its prefix is {@code type}'s. */
    public static boolean matches(ResourceType type, String id) {
        try {
            return typeOf(id) == type;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
