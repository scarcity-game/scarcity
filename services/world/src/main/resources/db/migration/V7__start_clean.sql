-- The resources are stored as the typed hierarchy now (#185): `state` holds the resource's JSON without
-- `id`, `version`, `children` and `resourceKind`, which have columns or are the type. The old rows are
-- the old shape, so this empties the schema (the world started less than a week before) and at startup
-- libs/world-seed sees no game and seeds the test area afresh.
TRUNCATE resources, area_log, area_log_head;
