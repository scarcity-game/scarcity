-- Walls have a material drawn per level, and physical values are derived (docs/decisions.md
-- 2026-11-24, "Physical values are derived"): a wall is the `wall` prototype of a material, weighing
-- its area of it 3 m tall, the stairs' masses are varied, and a game carries `materialPatterns`. The
-- stored walls are `stone-wall`s of 5000 kg each, the stored games have no patterns, and the games are
-- test data, so this empties the schema as V11 did: at startup libs/world-seed sees no game and seeds
-- afresh.
TRUNCATE resources, area_log, area_log_head;
