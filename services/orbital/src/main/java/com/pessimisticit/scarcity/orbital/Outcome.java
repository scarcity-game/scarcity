package com.pessimisticit.scarcity.orbital;

/**
 * How a turn ended: the {@code outcome} tag of {@code orbital.intents}, and how long the
 * loop waits before the next turn ({@link Pacing}).
 */
public enum Outcome {
    /** world accepted the intent. */
    OK("ok", Pace.INTERVAL),
    /** The entity already had an action; nothing was posted. */
    BUSY("busy", Pace.INTERVAL),
    /** 409: the entity changed (or started an action) since it was read. */
    STALE("stale", Pace.INTERVAL),
    /** 422: world rejected the choice; it sits out a while. */
    INVALID("invalid", Pace.INTERVAL),
    /** No choice could be made (all skipped, or the drawn one can't be made now). */
    UNAVAILABLE("unavailable", Pace.INTERVAL),
    /** 401 or 403: the key is wrong, or orbital doesn't control the entity. */
    DENIED("denied", Pace.LONGEST),
    /** 404: the entity doesn't exist. */
    NOT_FOUND("not_found", Pace.LONGEST),
    /** world didn't answer. */
    UNREACHABLE("unreachable", Pace.BACK_OFF),
    /** Anything else: a 5xx, an answer orbital can't read. */
    ERROR("error", Pace.BACK_OFF);

    /** How long to wait after an outcome. */
    enum Pace {
        /** The configured interval. */
        INTERVAL,
        /** Doubling from the interval with each such turn in a row, up to the longest. */
        BACK_OFF,
        /** The longest wait (30 s). */
        LONGEST
    }

    private final String tag;
    private final Pace pace;

    Outcome(String tag, Pace pace) {
        this.tag = tag;
        this.pace = pace;
    }

    public String tag() {
        return tag;
    }

    Pace pace() {
        return pace;
    }
}
