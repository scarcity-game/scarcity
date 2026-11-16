package com.pessimisticit.scarcity.world.db;

import java.time.Duration;
import java.util.Objects;

/**
 * How often an advance writes a snapshot and how long entries are kept
 * (docs/log.md, "Snapshots and pruning"), both in wall time.
 */
public record LogSettings(Duration snapshotInterval, Duration retention) {

    public LogSettings {
        Objects.requireNonNull(snapshotInterval, "snapshotInterval");
        Objects.requireNonNull(retention, "retention");
    }

    /** 60 minutes and 24 hours, as docs/log.md has them. */
    public static LogSettings defaults() {
        return new LogSettings(Duration.ofMinutes(60), Duration.ofHours(24));
    }
}
