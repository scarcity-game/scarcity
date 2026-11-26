package com.pessimisticit.scarcity.resourcemodel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The {@code LISTEN/NOTIFY} channels (docs/resources.md, "Change notifications"): one per area for its
 * batches, {@code area_<32 hex>}, and {@value #TURNS} for every area's turn notices. An area id of the form
 * {@code area:<uuid>} gives the UUID's hex digits; any other id (tests use {@code area:1}) gives the first
 * 32 hex digits of its SHA-256. Either way the name is a plain lowercase identifier, safe to put in a
 * {@code LISTEN} statement, and within Postgres's 63 bytes.
 */
public final class Channels {

    /** Every area's {@link TurnNotice}s. */
    public static final String TURNS = "resource_turns";

    private static final String PREFIX = "area_";
    private static final Pattern UUID_ID =
            Pattern.compile("area:([0-9a-f]{8})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{12})");
    private static final Pattern NAME = Pattern.compile("area_[0-9a-f]{32}|" + TURNS);

    private Channels() {
    }

    /** The channel {@code areaId}'s batches are sent on. */
    public static String area(String areaId) {
        var m = UUID_ID.matcher(areaId);
        if (m.matches()) {
            return PREFIX + m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5);
        }
        return PREFIX + HexFormat.of().formatHex(sha256(areaId), 0, 16);
    }

    /** Whether {@code channel} is one of ours (and so safe to put in a statement). */
    static boolean valid(String channel) {
        return NAME.matcher(channel).matches();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }
}
