-- Create area_log table
CREATE TABLE area_log (
    area_id   text NOT NULL,
    seq       bigint NOT NULL CHECK (seq >= 1),
    game_time double precision NOT NULL,
    wall_time timestamptz NOT NULL,
    kind      text NOT NULL,
    cause     bigint NULL,
    root      bigint NOT NULL,
    resource  text NULL,
    body      jsonb NOT NULL,
    PRIMARY KEY (area_id, seq)
);
-- Create index for kind
CREATE INDEX area_log_kind_idx ON area_log (area_id, kind, seq);
-- Create index for snapshot wall_time
CREATE INDEX area_log_snapshot_wall_idx ON area_log (area_id, wall_time) WHERE kind = 'snapshot';
-- Create area_log_head table
CREATE TABLE area_log_head (
    area_id          text PRIMARY KEY,
    last_seq         bigint NOT NULL,
    last_snapshot_at timestamptz NULL
);