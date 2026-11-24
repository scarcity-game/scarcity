-- A stash's radius comes from its items' mass, and items join a stash within twice that radius
-- (docs/resources.md, "Items and stashes"). The stored stashes have the fixed radius 0.5 and the
-- games are test data, so this empties the schema as V12 did: at startup libs/world-seed sees no
-- game and seeds afresh.
TRUNCATE resources, area_log, area_log_head;
