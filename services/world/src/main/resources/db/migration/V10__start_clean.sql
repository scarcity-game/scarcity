-- Every area is enclosed by four stone walls now (#296, #234): libs/world-seed seeds them with the
-- test area and the dive game, and Crossing makes them with each level. The stored areas have none
-- and the game is test data, so this empties the schema as V7 and V9 did: at startup libs/world-seed
-- sees no game and seeds afresh.
TRUNCATE resources, area_log, area_log_head;
