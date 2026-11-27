package com.pessimisticit.scarcity.controller;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * A wait that doubles from {@code first} with each {@link #next()} up to {@code max}, each one
 * jittered to a random point in its upper half, so clients cut off together (a subscriptions drain,
 * an Envoy restart) don't reconnect together (docs/websocket.md, "Reconnecting"). Not thread-safe.
 */
final class BackOff {

    private final Duration first;
    private final Duration max;
    /** A number in [0, 1); operational, not a game draw. */
    private final DoubleSupplier random;
    private Duration wait;

    BackOff(Duration first, Duration max) {
        this(first, max, () -> ThreadLocalRandom.current().nextDouble());
    }

    BackOff(Duration first, Duration max, DoubleSupplier random) {
        this.first = first;
        this.max = max;
        this.random = random;
        this.wait = first;
    }

    /** This wait, between half of it and all of it; the next one is twice as long, at most {@code max}. */
    Duration next() {
        Duration now = wait;
        Duration doubled = wait.multipliedBy(2);
        wait = doubled.compareTo(max) < 0 ? doubled : max;
        long half = now.toNanos() / 2;
        return Duration.ofNanos(half + (long) (random.getAsDouble() * (now.toNanos() - half)));
    }

    /** The next wait is {@code first} again. */
    void reset() {
        wait = first;
    }
}
