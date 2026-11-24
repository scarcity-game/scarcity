-- Every dive level is generated now (#311, #234): libs/world-gen lays out rooms and corridors, and
-- each level's log begins with how it was made. The stored dive levels are 10 x 8 rooms with no such
-- log, and the game is test data, so this empties the schema as V10 did: at startup libs/world-seed
-- sees no game and seeds afresh.
TRUNCATE resources, area_log, area_log_head;
