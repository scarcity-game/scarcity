-- The world starts clean (#111, #112): the tick loop's test area (area
-- `clock`, orbiter `behaviour`) is not ported. The schema holds only the test
-- area, so this empties it; at startup libs/world-seed sees no game and seeds
-- the test area afresh.
TRUNCATE resources, area_log, area_log_head;
