-- Items are resources, always in a stash (#81, docs/decisions.md 2026-11-24). The stored areas hold
-- pebble and rubble entities, some decaying with params that no longer read, and the games are test
-- data, so this empties the schema as V11 did: at startup libs/world-seed sees no game and seeds afresh.
TRUNCATE resources, area_log, area_log_head;

-- Loading an area reads its items by area (ResourceStore.inArea).
CREATE INDEX IF NOT EXISTS resources_area_type_idx ON resources (area_id, type);
