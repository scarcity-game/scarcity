package com.pessimisticit.scarcity.controller;

/** How a turn ended: the {@code outcome} tag of {@code <principal>.intents}. */
public enum Outcome {
    /** world accepted the intent. */
    OK("ok"),
    /** The entity has moved on since the turn frame (a newer version, or busy); nothing was posted. */
    STALE("stale"),
    /** 409: the entity changed, or is no longer up, since it was read. */
    CONFLICT("conflict"),
    /** 422 on a wait, the drawn one or the fallback: there is nothing left to try this turn. */
    INVALID("invalid"),
    /** No choice yielded an intent (none feasible, or a 422) and the entity can't wait. */
    UNAVAILABLE("unavailable"),
    /** No choice yielded an intent (none feasible, or a 422), so the turn was passed with a wait world accepted. */
    FALLBACK("fallback"),
    /** 401 or 403: the token is refused, or the principal doesn't control the entity. */
    DENIED("denied"),
    /** 404: the entity doesn't exist. */
    NOT_FOUND("not_found"),
    /** world or auth didn't answer. */
    UNREACHABLE("unreachable"),
    /** Anything else: a 5xx, an answer the controller can't read. */
    ERROR("error");

    private final String tag;

    Outcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
