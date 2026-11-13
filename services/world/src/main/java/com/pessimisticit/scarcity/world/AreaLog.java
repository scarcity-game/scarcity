package com.pessimisticit.scarcity.world;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The area log store (contract: {@code docs/log.md}). All writes happen inside the caller's
 * transaction (the tick's); the head row is locked for the whole tick.
 */
@Component
public class AreaLog {

    /** The last seq handed out for an area and when it last got a snapshot. */
    public record Head(long lastSeq, Instant lastSnapshotAt) {
    }

    public static final int MAX_LIMIT = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Duration snapshotInterval;
    private final Duration retention;

    @Autowired
    public AreaLog(JdbcTemplate jdbc,
            @Value("${scarcity.world.log.snapshot-interval-minutes:60}") long snapshotIntervalMinutes,
            @Value("${scarcity.world.log.retention-hours:24}") long retentionHours) {
        this.jdbc = jdbc;
        this.snapshotInterval = Duration.ofMinutes(snapshotIntervalMinutes);
        this.retention = Duration.ofHours(retentionHours);
    }

    /** Locks the area's head row (creating it if needed) and starts the log of one tick. */
    public TickLog begin(String areaId, Instant now) {
        return new TickLog(this, areaId, lockHead(areaId), now);
    }

    Head lockHead(String areaId) {
        jdbc.update("INSERT INTO area_log_head (area_id, last_seq, last_snapshot_at) VALUES (?, 0, NULL) "
                + "ON CONFLICT DO NOTHING", areaId);
        return jdbc.queryForObject("SELECT last_seq, last_snapshot_at FROM area_log_head WHERE area_id = ? FOR UPDATE",
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp("last_snapshot_at");
                    return new Head(rs.getLong("last_seq"), ts == null ? null : ts.toInstant());
                }, areaId);
    }

    boolean snapshotDue(Head head, Instant now) {
        return head.lastSnapshotAt() == null || !head.lastSnapshotAt().plus(snapshotInterval).isAfter(now);
    }

    void append(String areaId, Instant wallTime, List<LogEntry> entries) {
        Timestamp ts = Timestamp.from(wallTime);
        jdbc.batchUpdate("INSERT INTO area_log (area_id, seq, game_time, wall_time, kind, cause, root, resource, body) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", entries, entries.size(), (ps, e) -> {
                    ps.setString(1, areaId);
                    ps.setLong(2, e.seq);
                    ps.setDouble(3, e.time);
                    ps.setTimestamp(4, ts);
                    ps.setString(5, e.kind);
                    ps.setObject(6, e.cause, java.sql.Types.BIGINT);
                    ps.setLong(7, e.root);
                    ps.setString(8, e.resource);
                    ps.setString(9, e.body().toString());
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

    /** One page of the log (ascending seq, {@code seq > after}); first/last cover the whole area log. */
    public ObjectNode read(String areaId, long after, int limit, String kind) {
        List<Object> args = new ArrayList<>(List.of(areaId, after));
        String sql = "SELECT seq, game_time, wall_time, kind, cause, root, resource, body FROM area_log "
                + "WHERE area_id = ? AND seq > ?";
        if (kind != null) {
            sql += " AND kind = ?";
            args.add(kind);
        }
        sql += " ORDER BY seq LIMIT ?";
        args.add(limit);
        ArrayNode entries = JsonNodeFactory.instance.arrayNode();
        jdbc.query(sql, rs -> {
            long cause = rs.getLong("cause");
            boolean noCause = rs.wasNull();
            entries.add(entry(areaId, rs.getLong("seq"), rs.getDouble("game_time"),
                    rs.getTimestamp("wall_time").toInstant(), rs.getString("kind"), noCause ? null : cause,
                    rs.getLong("root"), rs.getString("resource"), rs.getString("body")));
        }, args.toArray());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("entries", entries);
        jdbc.query("SELECT min(seq) AS lo, max(seq) AS hi FROM area_log WHERE area_id = ?", rs -> {
            long lo = rs.getLong("lo");
            if (rs.wasNull()) {
                out.putNull("first");
                out.putNull("last");
            } else {
                out.put("first", lo);
                out.put("last", rs.getLong("hi"));
            }
        }, areaId);
        return out;
    }

    long rootOf(String areaId, long seq) {
        List<Long> result = jdbc.query("SELECT root FROM area_log WHERE area_id = ? AND seq = ?",
            (rs, i) -> rs.getLong(1), areaId, seq);
        if (result.isEmpty()) {
            return seq;
        }
        return result.get(0);
    }

    private static ObjectNode entry(String areaId, long seq, double time, Instant wallTime, String kind, Long cause,
            long root, String resource, String bodyText) {
        JsonNode body;
        try {
            body = MAPPER.readTree(bodyText);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable log body at seq " + seq, e);
        }
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("seq", seq);
        n.put("area", areaId);
        n.put("time", time);
        n.put("wallTime", wallTime.toString());
        n.put("kind", kind);
        if (body.has("reason")) {
            n.set("reason", body.get("reason"));
        }
        if (cause == null) {
            n.putNull("cause");
        } else {
            n.put("cause", cause);
        }
        n.put("root", root);
        if (resource == null) {
            n.putNull("resource");
        } else {
            n.put("resource", resource);
        }
        n.set("action", body.has("action") ? body.get("action") : JsonNodeFactory.instance.nullNode());
        n.set("draws", body.has("draws") ? body.get("draws") : JsonNodeFactory.instance.arrayNode());
        n.set("changes", body.get("changes"));
        if (body.has("effect")) {
            n.set("effect", body.get("effect"));
        }
        if (body.has("result")) {
            n.set("result", body.get("result"));
        }
        return n;
    }
}
