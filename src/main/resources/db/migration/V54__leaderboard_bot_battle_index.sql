-- ValoCase backend - leaderboard bot-battle read-path index.
--
-- Additive only: no data or behavior changes. Supports excluding lobby-resolved
-- battles from the legacy bot-battle leaderboard source (battles rows referenced
-- by battle_lobbies.result_battle_id) without a sequential scan.

CREATE INDEX IF NOT EXISTS idx_battle_lobbies_result_battle_id
    ON battle_lobbies (result_battle_id);
