package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.world.engine.LogEntry;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** The writes to {@code area_log} and {@code area_log_head}, inside the caller's transaction. */
final class LogTable {

    private final JdbcTemplate jdbc;
    private final Duration retention;

    LogTable(JdbcTemplate jdbc, Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    void append(String areaId, Instant wallTime, List<LogEntry> entries) {
        Timestamp ts = Timestamp.from(wallTime);
        jdbc.batchUpdate("INSERT INTO area_log (area_id, seq, game_time, wall_time, kind, cause, root, resource, body) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", entries, entries.size(), (ps, e) -> {
                    ps.setString(1, areaId);
                    ps.setLong(2, e.seq());
                    ps.setDouble(3, e.time());
                    ps.setTimestamp(4, ts);
                    ps.setString(5, e.kind().wire());
                    ps.setObject(6, e.cause(), Types.BIGINT);
                    ps.setLong(7, e.root());
                    ps.setString(8, e.resource());
                    ps.setString(9, e.bodyJson().toString());
                });
    }

    void saveHead(String areaId, long lastSeq, Instant lastSnapshotAt) {
        jdbc.update("UPDATE area_log_head SET last_seq = ?, last_snapshot_at = ? WHERE area_id = ?", lastSeq,
                lastSnapshotAt == null ? null : Timestamp.from(lastSnapshotAt), areaId);
    }

    /** Deletes the entries before the newest snapshot that is older than the retention. */
    int prune(String areaId, Instant now) {
        Timestamp limit = Timestamp.from(now.minus(retention));
        return jdbc.update("DELETE FROM area_log WHERE area_id = ? AND seq < (SELECT max(seq) FROM area_log "
                + "WHERE area_id = ? AND kind = 'snapshot' AND wall_time < ?)", areaId, areaId, limit);
    }

    /** The root of each of {@code seqs} the log still holds; a pruned one is missing. */
    Map<Long, Long> roots(String areaId, Collection<Long> seqs) {
        Map<Long, Long> roots = new HashMap<>();
        if (seqs.isEmpty()) {
            return roots;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT seq, root FROM area_log WHERE area_id = ? AND seq = ANY (?)");
            Array array = con.createArrayOf("bigint", seqs.toArray());
            ps.setString(1, areaId);
            ps.setArray(2, array);
            return ps;
        }, rs -> {
            roots.put(rs.getLong("seq"), rs.getLong("root"));
        });
        return roots;
    }
}
