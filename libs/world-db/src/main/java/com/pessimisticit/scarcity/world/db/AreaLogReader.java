package com.pessimisticit.scarcity.world.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The log's read query (docs/log.md, "Reading the log"). The service checks
 * the request's parameters; this reads what they ask for.
 */
public final class AreaLogReader {

    /** The most entries one read returns. */
    public static final int MAX_LIMIT = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public AreaLogReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code {"entries", "first", "last"}}: up to {@code limit} entries with
     * {@code seq > after} in ascending seq; {@code first}/{@code last} cover the
     * whole area log (null when it is empty). An unknown area reads as an empty log.
     *
     * @param limit 1..{@link #MAX_LIMIT}
     * @param kind null: every kind
     */
    public ObjectNode read(String areaId, long after, int limit, String kind) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit: must be 1.." + MAX_LIMIT + ", got " + limit);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("entries", entries(areaId, after, limit, kind));
        jdbc.query("SELECT min(seq) AS lo, max(seq) AS hi FROM area_log WHERE area_id = ?", rs -> {
            out.set("first", seqOrNull(rs, "lo"));
            out.set("last", seqOrNull(rs, "hi"));
        }, areaId);
        return out;
    }

    private ArrayNode entries(String areaId, long after, int limit, String kind) {
        List<Object> args = new ArrayList<>(List.of(areaId, after));
        StringBuilder sql = new StringBuilder("SELECT seq, game_time, wall_time, kind, cause, root, resource, body "
                + "FROM area_log WHERE area_id = ? AND seq > ?");
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        sql.append(" ORDER BY seq LIMIT ?");
        args.add(limit);
        ArrayNode entries = JsonNodeFactory.instance.arrayNode();
        jdbc.query(sql.toString(), rs -> {
            entries.add(entry(areaId, rs));
        }, args.toArray());
        return entries;
    }

    /** The columns, then the stored body's keys in their order (docs/log.md, "An entry"). */
    private static ObjectNode entry(String areaId, ResultSet rs) throws SQLException {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        long seq = rs.getLong("seq");
        n.put("seq", seq);
        n.put("area", areaId);
        n.put("time", rs.getDouble("game_time"));
        n.put("wallTime", rs.getTimestamp("wall_time").toInstant().toString());
        n.put("kind", rs.getString("kind"));
        n.set("cause", seqOrNull(rs, "cause"));
        n.put("root", rs.getLong("root"));
        n.put("resource", rs.getString("resource"));
        n.setAll(body(seq, rs.getString("body")));
        return n;
    }

    private static ObjectNode body(long seq, String text) {
        try {
            JsonNode body = MAPPER.readTree(text);
            if (!body.isObject()) {
                throw new IllegalStateException("log body at seq " + seq + " is not an object");
            }
            return (ObjectNode) body;
        } catch (IOException e) {
            throw new IllegalStateException("unreadable log body at seq " + seq, e);
        }
    }

    private static JsonNode seqOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.numberNode(value);
    }
}
