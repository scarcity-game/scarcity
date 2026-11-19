package com.pessimisticit.scarcity.controller;

import java.time.Duration;

/**
 * Optional bean: how long {@link Turns} waits after a turn frame before playing it. Without
 * one every turn waits {@code controller.think}. Called once per offered turn, under
 * {@link Turns}' lock, so an implementation may keep state without locking.
 */
@FunctionalInterface
public interface TurnDelay {

    /** The wall-clock wait before {@code turn} is played. */
    Duration before(TurnFrame turn);
}
