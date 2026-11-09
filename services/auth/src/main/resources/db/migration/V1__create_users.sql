-- Schema "auth" is created by Flyway itself (spring.flyway.schemas=auth,
-- create-schemas=true) before this migration runs, since its history table lives there.

CREATE TABLE auth.users (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    username      text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_username_format CHECK (username ~ '^[A-Za-z0-9_-]{3,32}$')
);

-- Usernames are unique case-insensitively but stored as entered.
CREATE UNIQUE INDEX users_username_lower_key ON auth.users (lower(username));
