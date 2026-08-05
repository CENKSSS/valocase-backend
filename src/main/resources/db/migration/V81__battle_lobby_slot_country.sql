-- ValoCase backend - country code on battle lobby slots.
--
-- The client shows each opponent's country next to their name, and the lobby
-- response is the only place it can get an opponent's country from. The slot
-- already denormalizes display_name and avatar_id from the account at
-- create/join time; country_code follows the exact same pattern and freshness.
--
-- Nullable, no backfill: NULL means the occupant never picked a country (or the
-- slot is EMPTY/BOT), and the client draws no label for it. The value is copied
-- from accounts.country_code, which V80's check constraint already restricts to
-- the ISO allowlist, so no constraint is repeated here.
--
-- Additive only: one new nullable column, existing rows stay NULL.

ALTER TABLE battle_lobby_slots
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);
