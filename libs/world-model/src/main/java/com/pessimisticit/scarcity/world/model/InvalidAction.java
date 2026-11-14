package com.pessimisticit.scarcity.world.model;

/**
 * An action that can't start, or a behaviour that can't pick one: the
 * message says why and names the key ({@code "step: to: outside the area"}).
 * The intents API answers it with 422; an engine that meets it on a
 * behaviour or an effect starts nothing (docs/actions.md).
 */
public class InvalidAction extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidAction(String reason) {
        super(reason);
    }

    public InvalidAction(String reason, Throwable cause) {
        super(reason, cause);
    }
}
