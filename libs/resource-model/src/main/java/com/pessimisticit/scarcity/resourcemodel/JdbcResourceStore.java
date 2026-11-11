package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@link ResourceStore} over the {@code resources} table (see resources.sql). */
public class JdbcResourceStore implements ResourceStore {

    /** Filter keys are bound as parameters anyway; this keeps them sane and keeps logs readable. */
    static final Pattern FILTER_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static final String SELECT = """
            SELECT r.id, r.type, r.version, r.state::text AS state,
                   (SELECT coalesce(array_agg(c.id ORDER BY c.created_at, c.id), '{}')
                      FROM resources c WHERE c.parent_id = r.id) AS children
              FROM resources r
            """;

    private static final RowMapper<Resource> MAPPER = (rs, i) -> {
        try {
            Array arr = rs.getArray("children");
            List<String> kids = arr == null ? List.of() : List.of((String[]) arr.getArray());
            return new Resource(rs.getString("id"), ResourceType.fromWire(rs.getString("type")),
                    rs.getLong("version"), kids, (ObjectNode) Json.MAPPER.readTree(rs.getString("state")));
        } catch (JsonProcessingException e) {
            throw new SQLException("bad state json", e);
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcResourceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Resource> get(String id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public boolean exists(String id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM resources WHERE id = ?)", Boolean.class, id));
    }

    @Override
    public Optional<String> areaOf(String id) {
        return jdbc.query("SELECT area_id FROM resources WHERE id = ?", (rs, i) -> rs.getString(1), id).stream()
                .filter(Objects::nonNull).findFirst();
    }

    @Override
    public Page<Resource> list(ResourceType type, Map<String, String> filter, int page, int size) {
        checkPaging(page, size);
        StringBuilder where = new StringBuilder(" WHERE r.type = ?");
        List<Object> args = new ArrayList<>();
        args.add(type.wire());
        for (Map.Entry<String, String> e : filter.entrySet()) {
            if (!FILTER_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("invalid filter key: " + e.getKey());
            }
            where.append(" AND r.state ->> ? = ?");
            args.add(e.getKey());
            args.add(e.getValue());
        }
        return page(where.toString(), args, page, size);
    }

    @Override
    public Page<Resource> children(String parentId, ResourceType type, int page, int size) {
        checkPaging(page, size);
        StringBuilder where = new StringBuilder(" WHERE r.parent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(parentId);
        if (type != null) {
            where.append(" AND r.type = ?");
            args.add(type.wire());
        }
        return page(where.toString(), args, page, size);
    }

    private Page<Resource> page(String where, List<Object> args, int page, int size) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM resources r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Resource> items = jdbc.query(SELECT + where + " ORDER BY r.created_at, r.id LIMIT ? OFFSET ?", MAPPER,
                pageArgs.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    private static void checkPaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be 1.." + MAX_SIZE);
        }
    }

    @Override
    public WriteResult create(Resource resource, String parentId) {
        if (!ResourceIds.matches(resource.type(), resource.id())) {
            throw new IllegalArgumentException("id " + resource.id() + " does not match type " + resource.type());
        }
        String parentArea = null;
        Long parentVersion = null;
        if (parentId != null) {
            // Bump (and row-lock) the parent first; it also yields the area to inherit.
            List<Object[]> rows = jdbc.query("""
                    UPDATE resources SET version = version + 1, updated_at = clock_timestamp()
                     WHERE id = ? RETURNING version, area_id
                    """, (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}, parentId);
            if (rows.isEmpty()) {
                throw new ResourceNotFoundException(parentId);
            }
            parentVersion = (Long) rows.get(0)[0];
            parentArea = (String) rows.get(0)[1];
        }
        String areaId = switch (resource.type()) {
            case AREA -> resource.id();
            case GAME -> null;
            default -> parentArea;
        };
        jdbc.update("""
                INSERT INTO resources (id, type, parent_id, area_id, version, state)
                VALUES (?, ?, ?, ?, 1, ?::jsonb)
                """, resource.id(), resource.type().wire(), parentId, areaId, stateJson(resource.state()));
        return new WriteResult(resource.id(), resource.type(), 1, areaId, parentId, parentVersion);
    }

    @Override
    public WriteResult updateState(String id, ObjectNode newState) {
        List<WriteResult> r = jdbc.query("""
                UPDATE resources SET state = ?::jsonb, version = version + 1, updated_at = clock_timestamp()
                 WHERE id = ? RETURNING type, version, area_id, parent_id
                """, (rs, i) -> new WriteResult(id, ResourceType.fromWire(rs.getString(1)), rs.getLong(2),
                rs.getString(3), rs.getString(4), null), stateJson(newState), id);
        if (r.isEmpty()) {
            throw new ResourceNotFoundException(id);
        }
        return r.get(0);
    }

    @Override
    public WriteResult delete(String id) {
        List<WriteResult> rows = jdbc.query("""
                SELECT type, version, area_id, parent_id FROM resources WHERE id = ? FOR UPDATE
                """, (rs, i) -> new WriteResult(id, ResourceType.fromWire(rs.getString(1)), rs.getLong(2),
                rs.getString(3), rs.getString(4), null), id);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(id);
        }
        WriteResult row = rows.get(0);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM resources WHERE parent_id = ?)", Boolean.class, id))) {
            throw new IllegalStateException("resource has children: " + id);
        }
        jdbc.update("DELETE FROM resources WHERE id = ?", id);
        Long parentVersion = null;
        if (row.parentId() != null) {
            parentVersion = jdbc.queryForObject("""
                    UPDATE resources SET version = version + 1, updated_at = clock_timestamp()
                     WHERE id = ? RETURNING version
                    """, Long.class, row.parentId());
        }
        return new WriteResult(id, row.type(), row.version(), row.areaId(), row.parentId(), parentVersion);
    }

    private static String stateJson(ObjectNode state) {
        try {
            return Json.MAPPER.writeValueAsString(state == null ? Json.MAPPER.createObjectNode() : state);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("state is not serialisable", e);
        }
    }
}
