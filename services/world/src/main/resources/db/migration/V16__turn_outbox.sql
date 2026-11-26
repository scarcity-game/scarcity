-- The turns world owes intelligence services (docs/actions.md, "Intelligence services"; #373). An
-- advance that leaves an area's active entity with a registered intelligence principal writes its
-- area's row in the same transaction; any other advance of the area deletes it. A turn is
-- (entity, since): the entity's next turn always has a later since. The dispatcher claims due rows
-- with FOR UPDATE SKIP LOCKED, moving next_at past a lease, and deletes the row once its intent is
-- applied or found stale.
CREATE TABLE turn_outbox (
    area_id    text PRIMARY KEY,
    entity     text NOT NULL,
    since      double precision NOT NULL,
    principal  text NOT NULL,
    attempts   integer NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    next_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX turn_outbox_due_idx ON turn_outbox (next_at);
