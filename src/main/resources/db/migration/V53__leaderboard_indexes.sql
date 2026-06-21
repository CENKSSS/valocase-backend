-- ValoCase backend - leaderboard read-path indexes.
--
-- Additive only: no data or behavior changes. These support the Battle-screen
-- leaderboard queries, which aggregate completed real-player lobby slots per
-- account and rank wallets by balance.

-- Speeds up grouping completed real-player slots by account.
CREATE INDEX IF NOT EXISTS idx_battle_lobby_slots_account_id
    ON battle_lobby_slots (account_id);

-- Speeds up the Highest Wallet Value ordering / rank counts.
CREATE INDEX IF NOT EXISTS idx_wallets_vp_balance
    ON wallets (vp_balance DESC);
