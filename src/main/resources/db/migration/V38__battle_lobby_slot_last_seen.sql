-- ValoCase backend - track real-player presence in battle lobby slots.
--
-- last_seen_at is refreshed for a real player's slot every time that player
-- polls the lobby status (a heartbeat). At resolution a real winner is only
-- granted rewards if their slot was seen within the connection window; a
-- disconnected winner receives no reward. NULL means "never seen" (treated as
-- disconnected). Bot and empty slots leave it NULL.

ALTER TABLE battle_lobby_slots
    ADD COLUMN last_seen_at TIMESTAMPTZ;
