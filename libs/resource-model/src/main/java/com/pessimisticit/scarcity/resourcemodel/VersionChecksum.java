package com.pessimisticit.scarcity.resourcemodel;

import java.util.Map;

/**
 * The version checksum (docs/resources.md, "Versions"): the sum, modulo 2³², of
 * {@code low32(id) × version} over a set of resources, where {@code low32(id)} is the id's last 8 hex
 * digits read as an unsigned 32-bit number. Java's {@code int} arithmetic wraps modulo 2³², as
 * TypeScript's {@code Math.imul} and {@code >>> 0} do, so both sides agree bit for bit. Order doesn't
 * matter, so a holder adds and subtracts terms as versions change.
 */
public final class VersionChecksum {

    private static final int HEX_DIGITS = 8;

    private VersionChecksum() {
    }

    /** One resource's term: {@code low32(id) × version} modulo 2³². */
    public static int term(String id, long version) {
        if (id.length() < HEX_DIGITS) {
            throw new IllegalArgumentException("not a resource id: " + id);
        }
        int low = Integer.parseUnsignedInt(id, id.length() - HEX_DIGITS, id.length(), 16);
        return low * (int) version;
    }

    /** The checksum of {@code versions} (id to version). */
    public static int of(Map<String, Long> versions) {
        int sum = 0;
        for (Map.Entry<String, Long> e : versions.entrySet()) {
            sum += term(e.getKey(), e.getValue());
        }
        return sum;
    }

    /** As it is sent: 8 lowercase hex digits, unsigned. */
    public static String hex(int checksum) {
        return String.format("%08x", checksum);
    }
}
