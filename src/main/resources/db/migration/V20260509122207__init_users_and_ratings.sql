-- Migration: init_users_and_ratings
-- Created: 2026-05-09T12:22:07.498201458

-- Users table mirrors Supabase auth.users.id.
-- Supabase Auth owns user creation; we lazily provision a row here on
-- first authenticated request to our API.
CREATE TABLE users (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE player_ratings (
    user_id        UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    rating         INT NOT NULL DEFAULT 1200,
    games_played   INT NOT NULL DEFAULT 0,
    peak_rating    INT NOT NULL DEFAULT 1200,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_player_ratings_rating_desc ON player_ratings (rating DESC);