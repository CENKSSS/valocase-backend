-- ValoCase backend - server-authoritative Free Lobby Event.
--
-- Every 2 minutes the backend creates exactly one public, FREE (entry cost 0)
-- event lobby. It appears in the normal public lobby list, starts empty (no real
-- host occupies a slot) and resolves through the existing battle rules.
--
-- A fixed, disabled system account owns event lobbies so the existing NOT NULL
-- foreign keys (battle_lobbies.creator_account_id and the resolved battles
-- header's account_id) are satisfied without a fake guest player. The account is
-- DISABLED so it never appears in any ACTIVE-only leaderboard, and it is given no
-- wallet, so it can neither be charged nor rewarded.
--
-- is_event marks a lobby as a system/event lobby server-side; event_window_key is
-- the 2-minute window the lobby belongs to. A UNIQUE constraint on event_window_key
-- is the database-level guard that lets only one event lobby exist per window even
-- when several backend instances run the scheduler at once (the second insert
-- collides and is ignored). Non-event lobbies keep a NULL key (NULLs are distinct
-- in Postgres, so they never collide).

INSERT INTO accounts (id, guest_token, display_name, status, created_at, last_seen_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-0000000000e1',
    'ValoCase Events',
    'DISABLED',
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE battle_lobbies
    ADD COLUMN is_event         BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN event_window_key VARCHAR(60);

CREATE UNIQUE INDEX uq_battle_lobbies_event_window
    ON battle_lobbies (event_window_key);
