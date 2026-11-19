package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Array;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;


/** {@link ResourceStore} over the {@code resources} table (see resources.sql). */
public class JdbcResourceStore implements ResourceStore {

    private static final String SELECT = """
            SELECT r.id, r.type, r.version, r.state::text AS state,
                   (SELECT coalesce(array_agg(c.id ORDER BY c.created_at, c.id), '{}')
                      FROM resources c WHERE c.parent_id = r.id) AS children
              FROM resources r
            """;

    private static final RowMapper<Resource> MAPPER = (rs, i) -> {
        Array arr = rs.getArray("children");
        List<String> kids = arr == null ? List.of() : List.of((String[]) arr.getArray());
        return StoredState.read(rs.getString("id"), ResourceKind.of(rs.getString("type")), rs.getLong("version"),
                kids, rs.getString("state"));
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
    public Optional<String> parentOf(String id) {
        return jdbc.query("SELECT parent_id FROM resources WHERE id = ?", (rs, i) -> rs.getString(1), id).stream()
                .filter(Objects::nonNull).findFirst();
    }

    @Override
    public Page<Resource> list(ResourceKind type, String gameId, Map<String, String> filter, int page, int size) {
        checkPaging(page, size);
        ListWhere where = ListWhere.of(type, gameId, filter);
        return page(where.sql(), where.args(), page, size);
    }

    @Override
    public Page<Resource> children(String parentId, ResourceKind type, int page, int size) {
        checkPaging(page, size);
        StringBuilder where = new StringBuilder(" WHERE r.parent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(parentId);
        if (type != null) {
            where.append(" AND r.type = ?");
            args.add(type.prefix());
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
        if (!matches(resource.resourceKind(), resource.id())) {
            throw new IllegalArgumentException("id " + resource.id() + " does not match type " + resource.resourceKind());
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
        String areaId = switch (resource.resourceKind()) {
            case AREA -> resource.id();
            case GAME -> null;
            case ENTITY -> parentArea;
        };
        jdbc.update("""
                INSERT INTO resources (id, type, parent_id, area_id, version, state)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, resource.id(), resource.resourceKind().prefix(), parentId, areaId, firstVersion(resource),
                StoredState.of(resource));
        return new WriteResult(resource.id(), resource.resourceKind(), firstVersion(resource), areaId, parentId, parentVersion);
    }

    @Override
    public WriteResult update(Resource resource) {
        String id = resource.id();
        List<WriteResult> r = jdbc.query("""
                UPDATE resources SET state = ?::jsonb, version = version + 1, updated_at = clock_timestamp()
                 WHERE id = ? RETURNING type, version, area_id, parent_id
                """, (rs, i) -> new WriteResult(id, ResourceKind.of(rs.getString(1)), rs.getLong(2),
                rs.getString(3), rs.getString(4), null), StoredState.of(resource), id);
        if (r.isEmpty()) {
            throw new ResourceNotFoundException(id);
        }
        return r.get(0);
    }

    @Override
    public WriteResult delete(String id) {
        List<WriteResult> rows = jdbc.query("""
                SELECT type, version, area_id, parent_id FROM resources WHERE id = ? FOR UPDATE
                """, (rs, i) -> new WriteResult(id, ResourceKind.of(rs.getString(1)), rs.getLong(2),
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
        return new WriteResult(id, row.resourceKind(), row.version(), row.areaId(), row.parentId(), parentVersion);
    }

    @Override
    public ChildrenWrite applyChildren(String parentId, List<Resource> creates, List<String> deletes,
            Resource newParent) {
        if (creates.isEmpty() && deletes.isEmpty() && newParent == null) {
            throw new IllegalArgumentException("nothing to apply");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : deletes) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("id listed twice: " + id);
            }
        }
        for (Resource c : creates) {
            if (!matches(c.resourceKind(), c.id())) {
                throw new IllegalArgumentException("id " + c.id() + " does not match type " + c.resourceKind());
            }
            if (!seen.add(c.id())) {
                throw new IllegalArgumentException("id listed twice or both created and deleted: " + c.id());
            }
        }
        // Lock the parent for the rest of the transaction; this also yields its area.
        List<String> parentRows = jdbc.query("SELECT area_id FROM resources WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), parentId);
        if (parentRows.isEmpty()) {
            throw new ResourceNotFoundException(parentId);
        }
        String parentArea = parentRows.get(0);
        List<WriteResult> deleted = new ArrayList<>();
        for (String id : deletes) {
            List<WriteResult> rows = jdbc.query("""
                    SELECT type, version, area_id FROM resources WHERE id = ? AND parent_id = ? FOR UPDATE
                    """, (rs, i) -> new WriteResult(id, ResourceKind.of(rs.getString(1)), rs.getLong(2),
                    rs.getString(3), parentId, null), id, parentId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException(id + " is not a child of " + parentId);
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM resources WHERE parent_id = ?)", Boolean.class, id))) {
                throw new IllegalStateException("resource has children: " + id);
            }
            deleted.add(rows.get(0));
        }
        for (String id : deletes) {
            jdbc.update("DELETE FROM resources WHERE id = ?", id);
        }
        List<WriteResult> created = new ArrayList<>();
        for (Resource c : creates) {
            String areaId = switch (c.resourceKind()) {
                case AREA -> c.id();
                case GAME -> null;
                case ENTITY -> parentArea;
            };
            jdbc.update("""
                    INSERT INTO resources (id, type, parent_id, area_id, version, state)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, c.id(), c.resourceKind().prefix(), parentId, areaId, firstVersion(c), StoredState.of(c));
            created.add(new WriteResult(c.id(), c.resourceKind(), firstVersion(c), areaId, parentId, null));
        }
        long parentVersion = jdbc.queryForObject("""
                UPDATE resources SET version = version + 1, updated_at = clock_timestamp(),
                       state = coalesce(?::jsonb, state)
                 WHERE id = ? RETURNING version
                """, Long.class, newParent == null ? null : StoredState.of(newParent), parentId);
        List<String> children = jdbc.queryForList(
                "SELECT id FROM resources WHERE parent_id = ? ORDER BY created_at, id", String.class, parentId);
        return new ChildrenWrite(parentId, parentVersion, children,
                created.stream().map(w -> withParentVersion(w, parentVersion)).toList(),
                deleted.stream().map(w -> withParentVersion(w, parentVersion)).toList());
    }

    /** 1, or the version a stored resource carries: an entity arriving from another area continues its sequence. */
    private static long firstVersion(Resource resource) {
        return resource.stored() ? resource.version() : 1L;
    }

    private static WriteResult withParentVersion(WriteResult w, long parentVersion) {
        return new WriteResult(w.id(), w.resourceKind(), w.version(), w.areaId(), w.parentId(), parentVersion);
    }

    private static boolean matches(ResourceKind kind, String id) {
        return Ids.isValid(id) && Ids.kindOf(id) == kind;
    }
}
