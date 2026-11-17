package com.pessimisticit.scarcity.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock() {
        this.now = Instant.parse("2026-11-14T12:00:00Z");
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
