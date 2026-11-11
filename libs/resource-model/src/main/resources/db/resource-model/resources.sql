-- resource-model: the one table behind ResourceStore. Idempotent; run it from the
-- owning service's Flyway migration (copy it in) or any other schema-init step.
-- The table name is deliberately unqualified: it lives in the calling service's
-- own schema (search_path), per "each service owns its schema".
CREATE TABLE IF NOT EXISTS resources (
    -- Type-prefixed global id: game:<uuid> | area:<uuid> | entity:<uuid> | item:<uuid>.
    id         text PRIMARY KEY,
    type       text NOT NULL CHECK (type IN ('game', 'area', 'entity', 'item')),
    -- Containing resource. Children of a resource are DERIVED from this column
    -- (ordered by created_at, id); there is no children array to keep in sync.
    parent_id  text NULL REFERENCES resources (id),
    -- The area a change belongs to, for notification batching ("one batch per area").
    --   area:   its own id
    --   entity/item: the area_id of its parent (copied on create)
    --   game (and anything parented outside an area): NULL
    -- Not an FK: it is denormalised routing data.
    area_id    text NULL,
    -- +1 on every change of the resource's own state or child list; starts at 1.
    version    bigint NOT NULL CHECK (version >= 1),
    -- Type-specific JSON object, opaque to the store (filters read state->>'key').
    state      jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(state) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- children(parentId, type) and the derived children list.
CREATE INDEX IF NOT EXISTS resources_parent_type_idx ON resources (parent_id, type);
-- list(type, filter).
CREATE INDEX IF NOT EXISTS resources_type_idx ON resources (type);
