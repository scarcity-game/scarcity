package com.pessimisticit.scarcity.orbital;

/** How a turn ended: the {@code outcome} tag of {@code orbital.intents}. */
public enum Outcome {
    /** world accepted the intent. */
    OK("ok"),
    /** The entity has moved on since the turn frame (a newer version, or busy); nothing was posted. */
    STALE("stale"),
    /** 409: the entity changed, or is no longer up, since it was read. */
    CONFLICT("conflict"),
    /** 422: world rejected the choice; it sits out a while. */
    INVALID("invalid"),
    /** No choice could be made (none of the entity's actions, all skipped, or the drawn one can't be made now). */
    UNAVAILABLE("unavailable"),
    /** 401 or 403: the token is refused, or orbital doesn't control the entity. */
    DENIED("denied"),
    /** 404: the entity doesn't exist. */
    NOT_FOUND("not_found"),
    /** world or auth didn't answer. */
    UNREACHABLE("unreachable"),
    /** Anything else: a 5xx, an answer orbital can't read. */
    ERROR("error");

    private final String tag;

    Outcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
