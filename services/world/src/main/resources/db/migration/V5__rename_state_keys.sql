-- #174 renamed the state keys of resources and of the log (docs/resources.md,
-- docs/log.md). Stored states in the old keys cannot be read, and the game is
-- test data with downtime allowed, so every resource and log row is removed;
-- the world service seeds the test area again at startup.
TRUNCATE area_log, area_log_head, resources;
