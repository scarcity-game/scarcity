-- A resource's children in listing order (parent, type, created_at, id), so
-- the children subquery, the child counts of a listing and a typed children
-- page read the index alone. It replaces (parent_id, type), its prefix.
-- Not CONCURRENTLY: Flyway holds its lock in an open transaction, which a
-- concurrent build waits on forever. Production got the index by hand
-- (CONCURRENTLY, 2026-11-27), so IF NOT EXISTS skips it there; elsewhere the
-- table is small and the build is quick.
CREATE INDEX IF NOT EXISTS resources_parent_type_order_idx
    ON resources (parent_id, type, created_at, id);

DROP INDEX IF EXISTS resources_parent_type_idx;
