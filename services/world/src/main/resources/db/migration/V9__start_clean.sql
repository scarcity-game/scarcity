-- Every entity but a placeholder is a physical object now, in SI units (#206, docs/decisions.md
-- 2026-11-18): it has a `mass` (grams) and a `radius` (metres), `overlap` and `damageable` are on
-- every entity, and only a damageable one has `hp`. The stored entities predate that (a mouse has
-- `hp` and no `damageable`, stairs and rubble have no radius), and the game is test data, so this
-- empties the schema as V7 did: at startup libs/world-seed sees no game and seeds afresh.
TRUNCATE resources, area_log, area_log_head;
