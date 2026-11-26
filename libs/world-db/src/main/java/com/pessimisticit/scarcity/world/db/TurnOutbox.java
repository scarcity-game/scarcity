package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The turns world owes intelligence services (docs/actions.md, "Intelligence services"), one row per
 * area: written by the transaction that commits the advance, so a turn is never lost or doubled by a
 * crash, and claimed with {@code FOR UPDATE SKIP LOCKED}, so two world pods never dispatch one turn
 * at once. Joins the caller's transaction and starts none.
 */
public final class TurnOutbox {

    private final JdbcTemplate jdbc;
    private final Predicate<String> intelligence;
    private final Runnable wake;

    /**
     * @param intelligence whether a service principal is a registered intelligence service
     * @param wake called after a transaction that wrote a row commits, so the dispatcher needn't wait
     *     for its next poll
     */
    public TurnOutbox(JdbcTemplate jdbc, Predicate<String> intelligence, Runnable wake) {
        this.jdbc = jdbc;
        this.intelligence = intelligence;
        this.wake = wake;
    }

    /** No intelligence services and nothing to wake: every {@link #offer} only clears its area's row. */
    public static TurnOutbox none(JdbcTemplate jdbc) {
        return new TurnOutbox(jdbc, principal -> false, () -> { });
    }

    /** A claimed turn: the area's active entity since {@code since}, played by {@code principal}. */
    public record Due(String areaId, String entity, double since, String principal, int attempts) {
    }

    /**
     * Writes {@code areaId}'s row for the active entity of {@code area} (its state after an advance) if
     * an intelligence service plays it, else deletes the row: the area's turn is someone else's, or nobody's.
     */
    public void offer(String areaId, Area area) {
        String principal = principal(area.activeEntity());
        if (principal == null) {
            jdbc.update("DELETE FROM turn_outbox WHERE area_id = ?", areaId);
            return;
        }
        Turn turn = area.activeEntity();
        int written = jdbc.update("""
                INSERT INTO turn_outbox (area_id, entity, since, principal) VALUES (?, ?, ?, ?)
                ON CONFLICT (area_id) DO UPDATE SET entity = excluded.entity, since = excluded.since,
                    principal = excluded.principal, attempts = 0, last_error = NULL, created_at = now(),
                    next_at = now()
                WHERE turn_outbox.entity <> excluded.entity OR turn_outbox.since <> excluded.since
                """, areaId, turn.entity(), turn.since(), principal);
        if (written > 0 && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake.run();
                }
            });
        }
    }

    /**
     * Claims up to {@code limit} due turns, oldest first, by moving each one's {@code next_at} past
     * {@code lease}: another dispatcher skips it until then, so a dispatcher that dies mid-call only
     * delays its turn. Rows another transaction holds are skipped, not waited for.
     */
    public List<Due> claim(int limit, Duration lease) {
        return jdbc.query("""
                UPDATE turn_outbox o SET next_at = now() + make_interval(secs => ?)
                  FROM (SELECT area_id FROM turn_outbox WHERE next_at <= now()
                         ORDER BY next_at LIMIT ? FOR UPDATE SKIP LOCKED) due
                 WHERE o.area_id = due.area_id
                RETURNING o.area_id, o.entity, o.since, o.principal, o.attempts
                """, (rs, i) -> new Due(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getString(4),
                        rs.getInt(5)), lease.toMillis() / 1000.0, limit);
    }

    /** The turn is done (its intent applied) or stale (a newer one exists): its row goes, a newer one stays. */
    public void finish(Due due) {
        jdbc.update("DELETE FROM turn_outbox WHERE area_id = ? AND entity = ? AND since = ?", due.areaId(),
                due.entity(), due.since());
    }

    /** The turn failed: counted, its error kept, tried again after {@code delay}. */
    public void retry(Due due, String error, Duration delay) {
        jdbc.update("""
                UPDATE turn_outbox SET attempts = attempts + 1, last_error = ?,
                    next_at = now() + make_interval(secs => ?)
                 WHERE area_id = ? AND entity = ? AND since = ?
                """, error, delay.toMillis() / 1000.0, due.areaId(), due.entity(), due.since());
    }

    /** The turns waiting per principal, and when the oldest of each was written. */
    public Map<String, Waiting> waiting() {
        Map<String, Waiting> out = new TreeMap<>();
        jdbc.query("SELECT principal, count(*), min(created_at) FROM turn_outbox GROUP BY principal", rs -> {
            Timestamp oldest = rs.getTimestamp(3);
            out.put(rs.getString(1), new Waiting(rs.getLong(2), oldest.toInstant()));
        });
        return out;
    }

    /** {@link #waiting()}'s value. */
    public record Waiting(long turns, Instant oldest) {
    }

    private String principal(Turn turn) {
        if (turn != null && turn.controller() instanceof Controller.Service service
                && intelligence.test(service.principal())) {
            return service.principal();
        }
        return null;
    }
}
