package com.pessimisticit.scarcity.world.engine;

/**
 * An advance that processed more than {@link Engine#MAX_STEPS} events and
 * effects: a rule that never stops. The caller rolls back.
 */
public final class RunawayAdvance extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunawayAdvance(String message) {
        super(message);
    }
}
