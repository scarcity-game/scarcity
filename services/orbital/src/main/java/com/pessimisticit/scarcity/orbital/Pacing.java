package com.pessimisticit.scarcity.orbital;

import java.time.Duration;

/** The wait after a turn, from its outcome. Not thread-safe: the loop is one thread. */
public final class Pacing {

    private final Duration interval;
    private final Duration longest;
    private int failures;

    public Pacing(Duration interval, Duration longest) {
        this.interval = interval;
        this.longest = longest;
    }

    /** The wait after a turn that ended with {@code outcome}. */
    public Duration after(Outcome outcome) {
        return switch (outcome.pace()) {
            case INTERVAL -> {
                failures = 0;
                yield interval;
            }
            case BACK_OFF -> backOff();
            case LONGEST -> {
                failures = 0;
                yield longest;
            }
        };
    }

    private Duration backOff() {
        failures = Math.min(failures + 1, Long.SIZE - 2);
        Duration wait = interval;
        for (int i = 0; i < failures && wait.compareTo(longest) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(longest) < 0 ? wait : longest;
    }
}
