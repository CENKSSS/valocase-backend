-- Backend-authoritative 24h mission claim cooldown.
-- next_reset_at is claimed_at + 24h; missions reset only once this server time passes.

ALTER TABLE player_missions ADD COLUMN next_reset_at TIMESTAMPTZ;

UPDATE player_missions
SET next_reset_at = claimed_at + INTERVAL '24 hours'
WHERE status = 'CLAIMED' AND claimed_at IS NOT NULL;
