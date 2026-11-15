-- Turns (#148) put `up` and `pending` in the area's state, written by every
-- advance and by the seed. The test area was seeded before #148 (after
-- V3__start_clean), so it has no `up`: subscriptions sends no turn, world
-- admits no intent, and the area can never advance.
--
-- This writes the queue (docs/actions.md, "Turns") into every area that has
-- no `up` and has never advanced (no area_log_head row). Such an area has
-- run nothing, so each controlled entity with no action in progress is idle
-- since the area's `time`; with equal `since` the order is by id (string
-- order, as Java's). The first is `up`, the rest `pending`. Each area
-- written gets version + 1. Areas with nobody controlled are left alone.
--
-- Computing `up` for any area that lacks it, at load time, is #158.
WITH fresh AS (
    SELECT a.id, (a.state ->> 'time')::double precision AS time
    FROM resources a
    WHERE a.type = 'area'
      AND NOT (a.state ? 'up')
      AND NOT EXISTS (SELECT 1 FROM area_log_head h WHERE h.area_id = a.id)
),
queue AS (
    SELECT f.id AS area_id,
           jsonb_build_object('entity', e.id,
                              'controller', e.state -> 'controller',
                              'since', f.time) AS turn,
           row_number() OVER (PARTITION BY f.id ORDER BY e.id COLLATE "C") AS n
    FROM fresh f
    JOIN resources e ON e.area_id = f.id AND e.type = 'entity'
    WHERE jsonb_typeof(e.state -> 'controller') = 'object'
      AND (e.state -> 'action') IS NULL
),
turns AS (
    SELECT area_id,
           (array_agg(turn ORDER BY n))[1] AS up,
           coalesce(jsonb_agg(turn ORDER BY n) FILTER (WHERE n > 1), '[]'::jsonb) AS pending
    FROM queue
    GROUP BY area_id
)
UPDATE resources a
SET state = a.state || jsonb_build_object('up', t.up, 'pending', t.pending),
    version = a.version + 1,
    updated_at = clock_timestamp()
FROM turns t
WHERE a.id = t.area_id;
