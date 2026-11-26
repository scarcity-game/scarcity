-- An area keeps its cells (docs/decisions.md 2026-11-25, docs/resources.md "Cells"): `cell` is a
-- resource type, a child of its area. The stored levels were made without cells, and the games are
-- test data, so this empties the schema as V14 did: at startup libs/world-seed sees no game and
-- seeds afresh, cells included.
ALTER TABLE resources DROP CONSTRAINT resources_type_check;
ALTER TABLE resources ADD CONSTRAINT resources_type_check
    CHECK (type IN ('game', 'area', 'entity', 'item', 'cell'));
TRUNCATE resources, area_log, area_log_head;
