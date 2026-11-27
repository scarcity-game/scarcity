-- Stairs name the area they lead to, `toArea`, beside the paired stairs `to` (#235); one is null
-- exactly when the other is. Linked stairs stored before this get the area that holds their pair.
-- The version stays as it is, as in V8: the area log's versions go on from it, and the log keeps the
-- stairs without `toArea` (it is history, served as stored).
UPDATE resources s
SET state = jsonb_set(s.state, '{toArea}', to_jsonb(pair.area_id))
FROM resources pair
WHERE s.type = 'entity'
  AND s.state->>'prototype' IN ('stairs-up', 'stairs-down')
  AND s.state->>'to' IS NOT NULL
  AND pair.id = s.state->>'to';
