package com.pessimisticit.scarcity.world.db;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The area's lock: its {@code area_log_head} row, {@code FOR UPDATE}. Every
 * advance of an area takes it first, so intents on one area run one at a
 * time and the log's seq has no gaps (docs/actions.md, "Intents").
 */
public final class AreaLock {

    private final JdbcTemplate jdbc;
    private final LogSettings settings;

    public AreaLock(JdbcTemplate jdbc, LogSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Creates the head if the area has none and locks it until the caller's
     * transaction ends. A second caller waits here, then reads the head the
     * first one committed.
     *
     * @param now the advance's wall time
     */
    public AreaHead begin(String areaId, Instant now) {
        jdbc.update("INSERT INTO area_log_head (area_id, last_seq, last_snapshot_at) VALUES (?, 0, NULL) "
                + "ON CONFLICT DO NOTHING", areaId);
        return jdbc.queryForObject("SELECT last_seq, last_snapshot_at FROM area_log_head WHERE area_id = ? "
                + "FOR UPDATE", (rs, i) -> {
                    Timestamp ts = rs.getTimestamp("last_snapshot_at");
                    Instant last = ts == null ? null : ts.toInstant();
                    return new AreaHead(areaId, rs.getLong("last_seq"), last, now, snapshotDue(last, now));
                }, areaId);
    }

    private boolean snapshotDue(Instant lastSnapshotAt, Instant now) {
        return lastSnapshotAt == null || !lastSnapshotAt.plus(settings.snapshotInterval()).isAfter(now);
    }
}
