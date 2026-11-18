-- Stairs are two prototypes now, `stairs-up` and `stairs-down` (#171, docs/decisions.md 2026-11-18),
-- and `stairs` is no longer one, so stored stairs are renamed. libs/world-seed made every stairs
-- entity there is, named "stairs up" or "stairs down"; any other name is down, as `stairs` were.
-- The area log keeps the old name: it is history, served as stored, never read back as a resource.
UPDATE resources
SET state = jsonb_set(state, '{prototype}',
        to_jsonb(CASE WHEN state->>'name' = 'stairs up' THEN 'stairs-up' ELSE 'stairs-down' END))
WHERE type = 'entity' AND state->>'prototype' = 'stairs';
