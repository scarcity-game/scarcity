-- #164: the area state's `up` is now `activeEntity` (docs/actions.md, "Turns").
-- Stored areas keep their game state: the key is renamed and the version goes
-- up, as for any change. The log cannot be rewritten the same way (its
-- snapshots and patches carry the old key in paths and states), so, as V5
-- did for #174, the area logs are dropped; an area without a log gets a new
-- one, starting with a snapshot, at its next advance.
UPDATE resources
SET state = (state - 'up') || jsonb_build_object('activeEntity', state -> 'up'),
    version = version + 1,
    updated_at = clock_timestamp()
WHERE type = 'area'
  AND state ? 'up';

TRUNCATE area_log, area_log_head;
