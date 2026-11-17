package com.pessimisticit.scarcity.world.engine;

/** An intent the engine won't start; nothing happened. The caller maps the reason to a status. */
public final class IntentRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why, with the status docs/actions.md gives it. */
    public enum Reason {
        /** 404: no such entity in the area. */
        UNKNOWN_ENTITY,
        /** 403: the entity has no controller (a behaviour or nothing decides for it). */
        NOT_CONTROLLED,
        /** 409: {@code ifVersion} isn't the entity's version. */
        STALE,
        /** 409: the entity already has an action. */
        BUSY,
        /**
         * 409: the entity is idle but pending: another one is the active entity, or none is, as no
         * player, npc or arrival is in the area (docs/actions.md, "Turns").
         */
        NOT_ACTIVE,
        /** 422: a start rule failed (the catalogue's message). */
        INVALID
    }

    private final Reason reason;

    public IntentRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
