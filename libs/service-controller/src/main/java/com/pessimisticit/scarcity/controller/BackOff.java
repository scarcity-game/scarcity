package com.pessimisticit.scarcity.controller;

import java.time.Duration;

/** A wait that doubles from {@code first} with each {@link #next()} up to {@code max}. Not thread-safe. */
final class BackOff {

    private final Duration first;
    private final Duration max;
    private Duration wait;

    BackOff(Duration first, Duration max) {
        this.first = first;
        this.max = max;
        this.wait = first;
    }

    /** This wait; the next one is twice as long, at most {@code max}. */
    Duration next() {
        Duration now = wait;
        Duration doubled = wait.multipliedBy(2);
        wait = doubled.compareTo(max) < 0 ? doubled : max;
        return now;
    }

    /** The next wait is {@code first} again. */
    void reset() {
        wait = first;
    }
}
